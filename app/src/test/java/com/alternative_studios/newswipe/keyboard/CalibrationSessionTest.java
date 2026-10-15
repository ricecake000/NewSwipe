package com.alternative_studios.newswipe.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

public class CalibrationSessionTest {
    /** 단모음 자판·NewSwipe 단모음에서 키를 한 번 눌러 치는 글자 (ㅊ·ㅌ은 NewSwipe 단모음에서 ㅈ·ㄷ를 밀지만 한 번 누른다). */
    private static final String ONE_PRESS = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎㅏㅐㅓㅔㅗㅜㅡㅣ ";

    private static CalibrationSession.Sample tap() {
        return new CalibrationSession.Sample(1, 2, 1, 2, 10, 20, "ㄱ", 0, 0, 100, 80);
    }

    @Test
    public void koreanSentencesUseOnlyOnePressLetters() {
        for (String s : CalibrationSession.KOREAN) {
            for (int i = 0; i < s.length(); i++) {
                for (String p : CalibrationSession.presses(s.charAt(i))) {
                    assertTrue(s + ": " + p, ONE_PRESS.contains(p));
                }
            }
        }
    }

    @Test
    public void englishSentencesUseOnlyLowercaseLetters() {
        for (String s : CalibrationSession.ENGLISH) assertTrue(s, s.matches("[a-z ]+"));
    }

    @Test
    public void thereAreEnoughSentencesToPickFrom() {
        assertTrue(CalibrationSession.KOREAN.length >= CalibrationSession.SENTENCES);
        assertTrue(CalibrationSession.ENGLISH.length >= CalibrationSession.SENTENCES);
    }

    @Test
    public void syllablesSplitIntoTheirKeys() {
        assertEquals(Arrays.asList("ㅎ", "ㅏ", "ㄴ"), CalibrationSession.presses('한'));
        assertEquals(Arrays.asList("ㅇ", "ㅏ"), CalibrationSession.presses('아'));
        assertEquals(Arrays.asList(" "), CalibrationSession.presses(' '));
        assertEquals(Arrays.asList("q"), CalibrationSession.presses('q'));
    }

    @Test
    public void walksThroughEveryLetterAndSpace() {
        CalibrationSession s = new CalibrationSession(true, false, new Random(1));
        s.attach("port_both");
        assertEquals(CalibrationSession.SENTENCES, s.sentenceCount());
        StringBuilder typed = new StringBuilder();
        while (s.running()) {
            typed.append(s.target());
            s.record(tap());
        }
        assertTrue(s.typed());
        assertNull(s.target());
        assertEquals(s.length(), s.samples().size());
        // 문장들을 이어 자모로 나눈 것과 같다.
        StringBuilder want = new StringBuilder();
        for (int i = 0; i < s.sentenceCount(); i++) {
            for (char c : s.sentence(i).toCharArray()) for (String p : CalibrationSession.presses(c)) want.append(p);
        }
        assertEquals(want.toString(), typed.toString());
    }

    @Test
    public void undoAndRestartSentence() {
        CalibrationSession s = new CalibrationSession(false, false, new Random(2));
        s.attach("port_both");
        String first = s.target();
        s.record(tap());
        s.record(tap());
        s.undo();
        assertEquals(1, s.position());
        s.restartSentence();
        assertEquals(0, s.position());
        assertEquals(first, s.target());
        assertTrue(s.samples().isEmpty());
        s.undo();   // 처음에서는 아무 일도 없다
        assertEquals(0, s.position());
    }

    @Test
    public void skippedLettersAreNotSamples() {
        CalibrationSession s = new CalibrationSession(true, false, new Random(3));
        s.attach("port_both");
        s.record(tap());
        s.skip();
        s.record(tap());
        assertEquals(3, s.position());
        assertEquals(2, s.samples().size());
    }

    @Test
    public void changingTheGripStartsOver() {
        CalibrationSession s = new CalibrationSession(true, false, new Random(4));
        s.attach("port_both");
        s.record(tap());
        s.record(tap());
        s.attach("port_both");   // 같은 파지법으로 다시 붙으면 (화면을 다시 만들 때) 그대로
        assertEquals(2, s.position());
        s.attach("land_both");
        assertEquals(0, s.position());
        assertEquals("land_both", s.grip());
        assertTrue(s.samples().isEmpty());
    }

    @Test
    public void listenersHearProgressAndEnd() {
        CalibrationSession s = new CalibrationSession(true, false, new Random(5));
        int[] ui = {0}, kb = {0};
        s.setUiListener(() -> ui[0]++);
        s.setKeyboardListener(() -> kb[0]++);
        s.attach("port_both");
        s.record(tap());
        assertEquals(2, ui[0]);
        s.setShowKeys(true);
        assertEquals(1, kb[0]);
        s.end();
        assertEquals(2, kb[0]);
        assertTrue(s.ended());
        assertFalse(s.running());
        s.record(tap());   // 끝난 뒤의 입력은 버린다
        assertEquals(2, ui[0]);
    }

    @Test
    public void completeKeepsTheResultOrFailure() {
        CalibrationSession s = new CalibrationSession(true, false, new Random(6));
        s.complete(null);
        assertTrue(s.failed());
        assertFalse(s.running());
    }

    @Test
    public void startReplacesTheActiveSession() {
        CalibrationSession a = CalibrationSession.start(true, false);
        CalibrationSession b = CalibrationSession.start(false, true);
        assertTrue(a.ended());
        assertEquals(b, CalibrationSession.active());
        assertTrue(b.showKeys());
        b.end();
        assertNull(CalibrationSession.active());
    }
}
