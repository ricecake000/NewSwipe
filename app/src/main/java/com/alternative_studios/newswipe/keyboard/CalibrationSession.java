package com.alternative_studios.newswipe.keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 터치 보정 한 번: 보여 줄 예시 문장, 지금 쳐야 할 글자, 그동안 모은 입력.
 *
 * <p>보정 화면(TouchCalibrationActivity)이 만들고, 키보드는 그 화면의 입력란에 붙었을 때 이것을 찾아 쓴다 (같은 프로세스).
 * 키보드는 키를 그리지 않고, 손가락이 닿을 때마다 지금 쳐야 할 글자의 키와 닿은 위치를 기록한다. 글자를 실제로 입력하지는 않는다.
 * 한 번 닿을 때 한 글자씩 나아가므로, 밀어서 입력하는 글자(쌍자음 등)도 키를 한 번 누르는 것으로 센다.
 * 문장에는 한 번 눌러서 치는 글자만 쓴다 (밀거나 두 번 눌러야 하는 ㅑ·ㅘ·ㄲ·겹받침은 사람마다 치는 방법이 달라 뺀다).
 *
 * <p>모든 호출은 메인 스레드에서 한다.
 */
public final class CalibrationSession {
    /** 보정 화면 입력란의 privateImeOptions. 키보드는 이 값과 이 앱의 입력란인지를 보고 보정을 시작한다. */
    public static final String IME_OPTION = "com.alternative_studios.newswipe.touchCalibration";
    /** 한 번에 치는 문장 수. */
    public static final int SENTENCES = 5;

    /** 한 번 눌러서 치는 글자만 쓴 문장 (ㅊ·ㅌ은 NewSwipe 단모음에서 ㅈ·ㄷ를 밀어 치지만 키는 한 번 누른다). */
    static final String[] KOREAN = {
            "나는 매일 아침 커피를 마신다",
            "주말에는 친구를 만나 산을 걷는다",
            "푸른 하늘 아래 바람이 분다",
            "작은 강아지가 문 앞에서 잠을 잔다",
            "시내 서점에서 새 책을 산다",
            "어머니가 만든 김밥은 늘 맛이 좋다",
            "버스를 타고 시내에 나간다",
            "추운 날에는 눈이 자주 내린다",
            "그는 매일 밤 일기를 적는다",
            "이른 아침에 동네 공터를 달린다",
            "새로 산 신발이 마음에 든다",
            "오늘 밤에는 비가 내릴 것 같다",
            "하루 종일 음악을 듣고 책을 본다",
    };
    static final String[] ENGLISH = {
            "the quick brown fox jumps over the lazy dog",
            "pack my box with five dozen liquor jugs",
            "how vexingly quick daft zebras jump",
            "sphinx of black quartz judge my vow",
            "the five boxing wizards jump quickly",
            "jackdaws love my big sphinx of quartz",
            "we promptly judged antique ivory buckles for the next prize",
            "a mad boxer shot a quick gloved jab to the jaw of his dizzy opponent",
    };

    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

    /** 키를 한 번 누른 기록. 좌표는 자판 뷰의 픽셀이다. */
    public static final class Sample {
        /** 손가락이 닿은 곳. */
        public final float x, y;
        /** 누르려던 키 (그때의 위치·크기). */
        public final float keyX, keyY, keyW, keyH;
        /** 누르려던 키를 알아보는 이름 (스페이스바는 " "). 보정 효과를 잴 때 닿은 키와 견준다. */
        public final String label;
        /** 그때 자판 안쪽의 왼쪽·위 위치와 폭·높이 (위치를 0~1로 바꾸는 데 쓴다). */
        public final float left, top, width, height;

        public Sample(float x, float y, float keyX, float keyY, float keyW, float keyH, String label,
                      float left, float top, float width, float height) {
            this.x = x;
            this.y = y;
            this.keyX = keyX;
            this.keyY = keyY;
            this.keyW = keyW;
            this.keyH = keyH;
            this.label = label;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    private static CalibrationSession active;

    /** 지금 진행 중인 보정. 없으면 null. */
    public static CalibrationSession active() {
        return active;
    }

    /** 새 보정을 시작한다 (진행 중이던 것은 버린다). */
    public static CalibrationSession start(boolean korean, boolean showKeys) {
        if (active != null) active.end();
        active = new CalibrationSession(korean, showKeys, new Random());
        return active;
    }

    public final boolean korean;
    private final String[] sentences;
    /** 문장의 글자를 한 번 누르는 단위로 늘어놓은 것: 칠 글자(자모·영어 글자, 띄어쓰기는 " "), 몇 번째 문장의 몇 번째 글자인지. */
    private final String[] targets;
    private final int[] sentenceOf, charOf;
    private final Sample[] samples;
    private int pos;
    private boolean ended;
    private boolean showKeys;
    /** 키보드가 알려 준 지금의 파지법 (Prefs.gripId). 보정 도중에 바뀌면 처음부터 다시 모은다. */
    private String grip;
    private TouchModel result;
    private boolean failed;
    private Runnable uiListener, keyboardListener;

    CalibrationSession(boolean korean, boolean showKeys, Random random) {
        this.korean = korean;
        this.showKeys = showKeys;
        List<String> pool = new ArrayList<>(java.util.Arrays.asList(korean ? KOREAN : ENGLISH));
        Collections.shuffle(pool, random);
        sentences = pool.subList(0, Math.min(SENTENCES, pool.size())).toArray(new String[0]);
        List<String> t = new ArrayList<>();
        List<Integer> s = new ArrayList<>(), c = new ArrayList<>();
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i];
            for (int j = 0; j < sentence.length(); j++) {
                for (String part : presses(sentence.charAt(j))) {
                    t.add(part);
                    s.add(i);
                    c.add(j);
                }
            }
        }
        targets = t.toArray(new String[0]);
        sentenceOf = new int[targets.length];
        charOf = new int[targets.length];
        for (int i = 0; i < targets.length; i++) {
            sentenceOf[i] = s.get(i);
            charOf[i] = c.get(i);
        }
        samples = new Sample[targets.length];
    }

    /** 글자 하나를 치려면 누르는 키들: 한글 음절은 초성·중성·종성으로 나눈다. */
    static List<String> presses(char ch) {
        List<String> out = new ArrayList<>(3);
        if (ch >= 0xAC00 && ch <= 0xD7A3) {
            int s = ch - 0xAC00;
            out.add(String.valueOf(CHO.charAt(s / (21 * 28))));
            out.add(String.valueOf(JUNG.charAt(s / 28 % 21)));
            if (s % 28 != 0) out.add(String.valueOf(JONG.charAt(s % 28)));
        } else {
            out.add(String.valueOf(ch));
        }
        return out;
    }

    // ---------------------------------------------------------------- 상태

    /** 문장을 다 쳐서 키보드가 결과를 맞출 차례다. */
    public boolean typed() {
        return !ended && result == null && !failed && pos == targets.length;
    }

    /** 아직 글자를 더 쳐야 하는 중 (끝나거나 그만두지 않았다). */
    public boolean running() {
        return !ended && result == null && !failed && pos < targets.length;
    }

    /** 키를 그리지 않고 둘 때 키 모양만 보여 줄지 (글자는 보이지 않는다). */
    public boolean showKeys() {
        return showKeys;
    }

    public void setShowKeys(boolean on) {
        if (showKeys == on) return;
        showKeys = on;
        notifyKeyboard();
    }

    /** 지금 쳐야 할 글자 (자모·영어 글자, 띄어쓰기는 " "). 다 쳤으면 null. */
    public String target() {
        return running() ? targets[pos] : null;
    }

    public int position() { return pos; }

    public int length() { return targets.length; }

    /** 지금 치고 있는 문장 번호 (0부터). 다 쳤으면 마지막 문장. */
    public int sentenceIndex() {
        return sentenceOf[Math.min(pos, targets.length - 1)];
    }

    public int sentenceCount() { return sentences.length; }

    public String sentence(int i) { return sentences[i]; }

    /** 지금 문장에서 치고 있는 글자의 위치 (다 쳤으면 문장 길이). */
    public int charIndex() {
        return pos < targets.length ? charOf[pos] : sentences[sentences.length - 1].length();
    }

    /** 키보드가 알려 준 지금의 파지법 (Prefs.gripId). 키보드가 붙기 전에는 null. */
    public String grip() { return grip; }

    public TouchModel result() { return result; }

    /** 입력이 문장과 많이 어긋나 결과를 낼 수 없었다. */
    public boolean failed() { return failed; }

    /** 기록한 입력 (건너뛴 글자는 빼고). */
    public List<Sample> samples() {
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples) if (s != null) out.add(s);
        return out;
    }

    // ---------------------------------------------------------------- 키보드에서

    /** 키보드가 보정 입력란에 붙을 때마다 지금의 파지법을 알린다. 모으는 도중에 파지법이 바뀌면 처음부터 다시 모은다. */
    public void attach(String gripId) {
        if (gripId.equals(grip)) return;
        boolean had = grip != null;
        grip = gripId;
        if (had && running()) {
            java.util.Arrays.fill(samples, null);
            pos = 0;
        }
        notifyUi();
    }

    /** 지금 글자의 키를 눌렀다. */
    public void record(Sample s) {
        if (!running()) return;
        samples[pos++] = s;
        notifyUi();
    }

    /** 지금 자판에 없는 글자는 건너뛴다 (스페이스바를 없앴을 때 등). */
    public void skip() {
        if (!running()) return;
        samples[pos++] = null;
    }

    /** 다 친 뒤 키보드가 맞춘 결과 (null이면 입력이 문장과 많이 어긋났다). */
    public void complete(TouchModel model) {
        if (ended) return;
        if (model == null) failed = true;
        else result = model;
        notifyUi();
        notifyKeyboard();
    }

    // ---------------------------------------------------------------- 보정 화면에서

    /** 방금 친 글자를 되돌린다. */
    public void undo() {
        if (!running() || pos == 0) return;
        samples[--pos] = null;
        notifyUi();
    }

    /** 지금 문장을 처음부터 다시 친다. */
    public void restartSentence() {
        if (!running()) return;
        int s = sentenceOf[pos];
        while (pos > 0 && sentenceOf[pos - 1] == s) samples[--pos] = null;
        notifyUi();
    }

    /** 보정을 그만둔다 (모은 입력은 버린다). */
    public void end() {
        if (ended) return;
        ended = true;
        if (active == this) active = null;
        notifyKeyboard();
        uiListener = null;
        keyboardListener = null;
    }

    public boolean ended() { return ended; }

    /** 진행이 바뀔 때마다 부를 일 (보정 화면). */
    public void setUiListener(Runnable r) { uiListener = r; }

    /** 키 보이기·끝남이 바뀔 때 부를 일 (키보드). 키보드 뷰를 새로 만들면 바꿔 끼운다. */
    public void setKeyboardListener(Runnable r) { keyboardListener = r; }

    private void notifyUi() {
        if (uiListener != null) uiListener.run();
    }

    private void notifyKeyboard() {
        if (keyboardListener != null) keyboardListener.run();
    }
}
