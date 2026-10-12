package com.alternative_studios.newswipe;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.alternative_studios.newswipe.keyboard.CalibrationSession;
import com.alternative_studios.newswipe.keyboard.TouchModel;
import com.alternative_studios.newswipe.ui.ExpressiveSwitch;
import com.alternative_studios.newswipe.ui.Ui;

/**
 * 터치 보정 화면. 키가 보이지 않는 키보드로 예시 문장을 치게 해서, 키를 누르려 할 때 손가락이 실제로 닿는 곳을 잰다.
 * 결과는 파지법(화면 방향·한 손 모드)마다 따로 저장하고, 적용 방식(터치 위치 보정·자판 모양 보정)을 고른다.
 *
 * <p>가로 모드도 보정할 수 있도록 이 화면만 세로로 고정하지 않는다 (돌려도 화면을 다시 만들지 않는다).
 * 키보드는 이 화면 입력란의 privateImeOptions(CalibrationSession.IME_OPTION)를 보고 보정을 시작한다.
 */
public final class TouchCalibrationActivity extends Activity {
    private static final String TITLE = "터치 보정";

    private Prefs prefs;
    private int bg, card, textColor, hintColor, accent, onAccent;
    private LinearLayout list;
    private ScrollView scroll;
    /** 키보드를 붙일 입력란. 글자는 입력되지 않으므로 보이지 않게 둔다. */
    private EditText input;
    private CalibrationSession session;
    /** 보정 시작 전에 고르는 문장 언어와 키 모양 보이기. */
    private boolean korean, showKeys;
    /** 마지막 보정의 결과 안내 (다음 보정을 시작하면 지운다). */
    private String lastStatus;

    private TextView gripView, sentenceView, nextView, progressView, statusView;
    private View idleBox, runningBox;
    private LinearLayout savedCard;
    private TextView modeNote;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppTheme.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        AppTheme colors = AppTheme.of(this);
        bg = colors.bg;
        card = colors.card;
        textColor = colors.text;
        hintColor = colors.hint;
        accent = colors.accent;
        onAccent = colors.onAccent;
        korean = prefs.korean();

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        list.setPadding(pad, 0, pad, Ui.dp(this, 32));
        scroll.addView(list);
        setContentView(new SettingsFrame(this, TITLE, getString(R.string.app_name)).withoutTestKeyboard().wrap(scroll));
        build();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.end();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refresh();   // 보정 전이면 지금 화면 방향의 파지법을 다시 보여 준다 (보정 중이면 키보드가 알려 준다)
    }

    // ---------------------------------------------------------------- 화면

    private void build() {
        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPrivateImeOptions(CalibrationSession.IME_OPTION);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setBackground(null);
        input.setCursorVisible(false);
        input.setAlpha(0f);
        input.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        list.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout run = card(0);
        gripView = text("", 15, textColor);
        gripView.setTypeface(Typeface.DEFAULT_BOLD);
        run.addView(gripView);
        run.addView(note("화면 방향(세로·가로·대화면)과 한 손 모드(끔·왼쪽·오른쪽)마다 보정을 따로 저장합니다. "
                + "보정할 자세로 기기를 쥐고, 그 자세에서 쓰는 화면 방향과 한 손 모드로 시작하세요."));

        LinearLayout idle = new LinearLayout(this);
        idle.setOrientation(LinearLayout.VERTICAL);
        idle.addView(label("문장 언어"));
        idle.addView(Ui.choiceRow(this, new String[]{"한글", "영어"}, korean ? 0 : 1, accent, onAccent, textColor,
                hintColor, 0, i -> korean = i == 0));
        idle.addView(toggle("키 모양 보이기",
                "글자 없이 키 모양만 보여 줍니다. 끄면 키보드 자리가 비어 있어, 키를 보지 않고 평소 손 위치대로 칠 때의 위치를 잴 수 있습니다.",
                showKeys, on -> showKeys = on));
        idle.addView(primaryButton("보정 시작", v -> start()));
        idleBox = idle;
        run.addView(idle);

        LinearLayout running = new LinearLayout(this);
        running.setOrientation(LinearLayout.VERTICAL);
        sentenceView = text("", 22, textColor);
        sentenceView.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        sentenceView.setLineSpacing(Ui.dp(this, 4), 1f);
        // 키보드가 내려갔으면 문장을 눌러 다시 연다.
        sentenceView.setOnClickListener(v -> showKeyboard());
        running.addView(sentenceView);
        nextView = text("", 15, accent);
        running.addView(nextView);
        progressView = text("", 13, hintColor);
        progressView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        running.addView(progressView);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        int barHeight = Ui.dp(this, 44);
        TextView undo = Ui.chipButton(this, "한 글자 되돌리기", textColor, Ui.tint(textColor), barHeight);
        undo.setOnClickListener(v -> { if (session != null) session.undo(); });
        buttons.addView(undo);
        TextView again = Ui.chipButton(this, "문장 다시", textColor, Ui.tint(textColor), barHeight);
        again.setOnClickListener(v -> { if (session != null) session.restartSentence(); });
        buttons.addView(again);
        TextView stop = Ui.chipButton(this, "그만두기", textColor, Ui.tint(textColor), barHeight);
        stop.setOnClickListener(v -> stop(null));
        buttons.addView(stop);
        running.addView(buttons);
        running.addView(note("키가 보이지 않아도 평소처럼 치세요. 쌍자음처럼 밀어서 치는 글자도 키를 한 번 누르는 것으로 셉니다. "
                + "글자는 입력되지 않고, 키보드가 내려가면 문장을 눌러 다시 열 수 있습니다."));
        runningBox = running;
        run.addView(running);

        statusView = text("", 14, textColor);
        statusView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        run.addView(statusView);

        LinearLayout mode = section("적용 방식");
        String[] modes = {Prefs.TOUCH_CALIB_OFF, Prefs.TOUCH_CALIB_TOUCH, Prefs.TOUCH_CALIB_LAYOUT};
        int selected = java.util.Arrays.asList(modes).indexOf(prefs.touchCalibrationMode());
        mode.addView(Ui.choiceRow(this, new String[]{"쓰지 않기", "터치 위치", "자판 모양"}, selected, accent, onAccent,
                textColor, hintColor, 0, i -> {
                    prefs.raw().edit().putString(Prefs.TOUCH_CALIB_MODE, modes[i]).apply();
                    modeNote.setText(modeDescription(modes[i]));
                }));
        modeNote = note(modeDescription(prefs.touchCalibrationMode()));
        mode.addView(modeNote);

        savedCard = section("저장된 보정");
    }

    private static String modeDescription(String mode) {
        switch (mode) {
            case Prefs.TOUCH_CALIB_OFF:
                return "보정 결과를 지우지 않고 쓰지 않습니다.";
            case Prefs.TOUCH_CALIB_LAYOUT:
                return "키를 손가락이 닿는 곳으로 옮겨 그립니다. 줄은 위아래로, 키는 줄 안에서 좌우로 경계가 움직여 "
                        + "자주 비켜 누르는 쪽의 키가 넓어집니다. 자판 바깥으로는 넘어가지 않습니다.";
            default:
                return "키는 그대로 두고, 손가락이 닿은 곳을 누르려던 곳으로 고쳐 읽습니다. 자판 모양은 바뀌지 않습니다.";
        }
    }

    /** 저장된 보정 목록과 진행 상태를 다시 그린다. */
    private void refresh() {
        boolean running = session != null && session.running();
        idleBox.setVisibility(running ? View.GONE : View.VISIBLE);
        runningBox.setVisibility(running ? View.VISIBLE : View.GONE);
        String grip = session != null && session.grip() != null ? session.grip()
                : Prefs.gripId(prefs.profileFor(this), prefs.oneHand());
        gripView.setText(running && session.grip() == null ? "키보드를 기다리는 중…"
                : "지금 파지법: " + Prefs.gripName(grip));
        if (running) showProgress();
        statusView.setText(lastStatus == null ? "" : lastStatus);
        statusView.setVisibility(lastStatus == null ? View.GONE : View.VISIBLE);
        refreshSaved();
    }

    private void showProgress() {
        int index = session.sentenceIndex();
        String sentence = session.sentence(index);
        int at = session.charIndex();
        SpannableStringBuilder sb = new SpannableStringBuilder(sentence);
        if (at < sentence.length() && sentence.charAt(at) == ' ') sb.replace(at, at + 1, "␣");
        sb.setSpan(new ForegroundColorSpan(hintColor), 0, Math.min(at, sb.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (at < sb.length()) {
            sb.setSpan(new ForegroundColorSpan(accent), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new UnderlineSpan(), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sentenceView.setText(sb);
        String target = session.target();
        nextView.setText(target == null ? "" : "다음: " + (" ".equals(target) ? "띄어쓰기" : target));
        progressView.setText("문장 " + (index + 1) + "/" + session.sentenceCount() + " · 입력 "
                + session.position() + "/" + session.length());
    }

    private void refreshSaved() {
        savedCard.removeAllViews();
        boolean any = false;
        for (String screen : Prefs.GRIP_SCREENS) {
            for (String hand : Prefs.GRIP_HANDS) {
                String grip = Prefs.gripId(screen, hand);
                TouchModel m = prefs.touchModel(grip);
                if (m == null) continue;
                any = true;
                savedCard.addView(savedRow(grip, m));
            }
        }
        if (!any) {
            savedCard.addView(note("아직 저장된 보정이 없습니다."));
            return;
        }
        TextView clear = Ui.chipButton(this, "모두 지우기", Ui.danger(AppTheme.isDark(this, prefs)),
                Ui.tint(textColor), Ui.dp(this, 44));
        clear.setOnClickListener(v -> confirmDelete("모든 파지법의 보정을 지울까요?", () -> {
            prefs.clearTouchModels();
            refresh();
        }));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.addView(clear);
        savedCard.addView(row);
    }

    private View savedRow(String grip, TouchModel m) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(Prefs.gripName(grip), 16, textColor));
        texts.addView(text(summary(m), 13, hintColor));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView delete = Ui.chipButton(this, "지우기", textColor, Ui.tint(textColor), Ui.dp(this, 44));
        delete.setOnClickListener(v -> confirmDelete(Prefs.gripName(grip) + "의 보정을 지울까요?", () -> {
            prefs.removeTouchModel(grip);
            refresh();
        }));
        row.addView(delete);
        return row;
    }

    /** 보정 결과 한 줄 요약: 입력 수, 평균적으로 비켜 닿은 쪽, 누르려던 키에 닿은 비율. */
    private static String summary(TouchModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append("입력 ").append(m.samples).append("개 · ").append(lean(m.meanDx, m.meanDy));
        if (m.rawHitPct >= 0) {
            sb.append("\n누르려던 키에 닿음: 보정 없이 ").append(m.rawHitPct).append("% → 터치 위치 ")
                    .append(m.touchHitPct).append("% · 자판 모양 ").append(m.layoutHitPct).append('%');
        }
        return sb.toString();
    }

    /** 평균적으로 비켜 닿은 쪽 (키 크기 단위, 0.1칸 단위로). */
    private static String lean(float dx, float dy) {
        String v = Math.abs(dy) < 0.05f ? null : (dy > 0 ? "아래로 " : "위로 ") + tenths(dy) + "칸";
        String h = Math.abs(dx) < 0.05f ? null : (dx > 0 ? "오른쪽으로 " : "왼쪽으로 ") + tenths(dx) + "칸";
        if (v == null && h == null) return "거의 키 가운데를 누름";
        return "평균 " + (v == null ? h : h == null ? v : v + ", " + h) + " 치우침";
    }

    private static String tenths(float f) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.abs(f));
    }

    // ---------------------------------------------------------------- 보정 진행

    private void start() {
        if (!OnboardingActivity.keyboardState(this)[1]) {
            lastStatus = "NewSwipe가 지금 쓰는 키보드여야 보정할 수 있습니다. 키보드를 NewSwipe로 바꾼 뒤 다시 시작하세요.";
            refresh();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return;
        }
        lastStatus = null;
        session = CalibrationSession.start(korean, showKeys);
        session.setUiListener(this::onSessionChanged);
        refresh();
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        input.requestFocus();
        // 키보드가 이미 이 입력란에 붙어 있어도 새 보정을 알아채도록 입력을 다시 시작한다.
        if (imm != null) imm.restartInput(input);
        showKeyboard();
    }

    private void showKeyboard() {
        input.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (!input.hasFocus()) input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void onSessionChanged() {
        if (session == null) return;
        if (session.result() != null) {
            String grip = session.grip();
            TouchModel m = session.result();
            prefs.setTouchModel(grip, m);
            String status = Prefs.gripName(grip) + " 보정을 저장했습니다.";
            if (m.rawHitPct >= 0) {
                status += " 누르려던 키에 닿은 비율이 보정 없이 " + m.rawHitPct + "%, 터치 위치 보정으로 " + m.touchHitPct
                        + "%, 자판 모양 보정으로 " + m.layoutHitPct + "%입니다 (방금 친 입력 기준).";
            }
            stop(status);
            return;
        }
        if (session.failed()) {
            stop("입력이 문장과 많이 어긋나 보정하지 못했습니다. 문장의 글자를 하나씩 따라 치며 다시 시작해 주세요.");
            return;
        }
        refresh();
    }

    /** 보정을 끝내고 키보드를 내린다. status는 결과 안내 (없으면 null). */
    private void stop(String status) {
        if (session != null) {
            session.end();
            session = null;
        }
        lastStatus = status;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        refresh();
    }

    private void confirmDelete(String message, Runnable onConfirm) {
        AppTheme.accentBuilder(AppTheme.dialogContext(this))
                .setTitle("보정 지우기")
                .setMessage(message)
                .setPositiveButton("지우기", (d, w) -> onConfirm.run())
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------------------------------------------------------------- 화면 조각

    private LinearLayout card(int topMarginDp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(card, Ui.dp(this, 16)));
        int p = Ui.dp(this, 16);
        c.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topMarginDp + 8);
        list.addView(c, lp);
        return c;
    }

    private LinearLayout section(String name) {
        TextView h = text(name, 14, accent);
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 20), 0, 0);
        list.addView(h);
        return card(0);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 16, textColor);
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 13, hintColor);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return t;
    }

    private View toggle(String title, String desc, boolean value, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, textColor));
        texts.addView(text(desc, 13, hintColor));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ExpressiveSwitch sw = new ExpressiveSwitch(this, accent, onAccent, hintColor, card);
        sw.setCheckedImmediately(value);
        sw.setOnCheckedChangeListener(checked -> onChange.accept(checked));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(this, 12);
        row.addView(sw, slp);
        row.setOnClickListener(v -> sw.performClick());
        return row;
    }

    /** 강조색 바탕의 큰 버튼. */
    private TextView primaryButton(String s, View.OnClickListener l) {
        TextView b = text(s, 16, onAccent);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int r = Ui.dp(this, 24);
        b.setBackground(Ui.ripple(Ui.tint(onAccent), Ui.round(accent, r), r));
        b.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        lp.bottomMargin = Ui.dp(this, 4);
        b.setLayoutParams(lp);
        return b;
    }
}
