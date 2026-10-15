package com.alternative_studios.newswipe.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class TouchModelTest {
    /** 10열 4줄 자판의 키 가운데를 고르게 누른 입력. 손가락이 닿는 곳까지의 차이는 field가 정한다. */
    private interface Field {
        float[] at(float u, float v);
    }

    private static final class Data {
        final int n;
        final float[] tu, tv, du, dv, kw, kh;

        Data(int n) {
            this.n = n;
            tu = new float[n];
            tv = new float[n];
            du = new float[n];
            dv = new float[n];
            kw = new float[n];
            kh = new float[n];
        }

        /** 앞의 k개만. */
        Data subset(int k) {
            Data d = new Data(k);
            for (float[][] pair : new float[][][]{{tu, d.tu}, {tv, d.tv}, {du, d.du}, {dv, d.dv}, {kw, d.kw}, {kh, d.kh}}) {
                System.arraycopy(pair[0], 0, pair[1], 0, k);
            }
            return d;
        }

        TouchModel fit(boolean[] used) {
            return TouchModel.fit(tu, tv, du, dv, kw, kh, n, used);
        }
    }

    private static Data grid(int repeat, Field field, long seed, float noise) {
        Random r = new Random(seed);
        Data d = new Data(40 * repeat);
        int i = 0;
        for (int k = 0; k < repeat; k++) {
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 10; col++) {
                    float u = (col + 0.5f) / 10f, v = (row + 0.5f) / 4f;
                    float[] o = field.at(u, v);
                    d.tu[i] = u;
                    d.tv[i] = v;
                    d.du[i] = o[0] + (float) r.nextGaussian() * noise * 0.1f;
                    d.dv[i] = o[1] + (float) r.nextGaussian() * noise * 0.25f;
                    d.kw[i] = 0.1f;
                    d.kh[i] = 0.25f;
                    i++;
                }
            }
        }
        return d;
    }

    @Test
    public void recoversAConstantLean() {
        TouchModel m = grid(3, (u, v) -> new float[]{0.01f, 0.05f}, 1, 0.1f).fit(null);
        assertNotNull(m);
        float[] o = new float[2];
        m.offset(0.5f, 0.5f, o);
        assertEquals(0.01f, o[0], 0.003f);
        assertEquals(0.05f, o[1], 0.005f);
        assertEquals(0.1f, m.meanDx, 0.03f);   // 키 폭 0.1의 0.1칸
        assertEquals(0.2f, m.meanDy, 0.03f);   // 키 높이 0.25의 0.2칸
        assertEquals(120, m.samples);
    }

    @Test
    public void recoversALeanThatGrowsTowardTheEdges() {
        // 오른쪽 키일수록 더 오른쪽을, 아래 줄일수록 더 아래를 누른다.
        TouchModel m = grid(3, (u, v) -> new float[]{0.02f * (2 * u - 1), 0.04f * (2 * v - 1)}, 2, 0f).fit(null);
        assertNotNull(m);
        float[] o = new float[2];
        m.offset(0.95f, 0.875f, o);
        assertEquals(0.018f, o[0], 0.002f);
        assertEquals(0.03f, o[1], 0.003f);
        m.offset(0.05f, 0.125f, o);
        assertEquals(-0.018f, o[0], 0.002f);
        assertEquals(-0.03f, o[1], 0.003f);
    }

    @Test
    public void dropsTapsThatHitADifferentKey() {
        Data d = grid(2, (u, v) -> new float[]{0f, 0.03f}, 3, 0.1f);
        // 문장과 어긋나 전혀 다른 키를 친 입력 몇 개.
        for (int i = 0; i < 8; i++) {
            d.du[i * 9] = 0.4f;
            d.dv[i * 9] = -0.5f;
        }
        boolean[] used = new boolean[d.n];
        TouchModel m = d.fit(used);
        assertNotNull(m);
        for (int i = 0; i < 8; i++) assertFalse(used[i * 9]);
        assertEquals(d.n - 8, m.samples);
        float[] o = new float[2];
        m.offset(0.5f, 0.5f, o);
        assertEquals(0f, o[0], 0.003f);
        assertEquals(0.03f, o[1], 0.005f);
    }

    @Test
    public void needsEnoughGoodTaps() {
        assertNull(grid(1, (u, v) -> new float[]{0f, 0f}, 4, 0f).subset(TouchModel.MIN_SAMPLES - 1).fit(null));
        assertNotNull(grid(1, (u, v) -> new float[]{0f, 0f}, 4, 0f).subset(TouchModel.MIN_SAMPLES).fit(null));
        // 절반 넘게 어긋났으면 문장을 따라 치지 않은 것으로 본다.
        Data d = grid(2, (u, v) -> new float[]{0f, 0f}, 5, 0f);
        for (int i = 0; i < d.n / 2 + 1; i++) d.du[i] = 0.5f;
        assertNull(d.fit(null));
    }

    @Test
    public void targetUndoesTheOffset() {
        TouchModel m = grid(3, (u, v) -> new float[]{0.015f * (2 * u - 1) + 0.005f, 0.04f * v}, 6, 0f).fit(null);
        assertNotNull(m);
        float[] t = new float[2], o = new float[2];
        for (float u = 0.1f; u < 1f; u += 0.2f) {
            for (float v = 0.1f; v < 1f; v += 0.2f) {
                m.target(u, v, t);
                m.offset(t[0], t[1], o);
                assertEquals(u, t[0] + o[0], 1e-4f);
                assertEquals(v, t[1] + o[1], 1e-4f);
            }
        }
    }

    @Test
    public void staysBoundedOutsideTheTypedArea() {
        TouchModel m = grid(3, (u, v) -> new float[]{0.03f * (2 * u - 1), 0.05f * (2 * v - 1)}, 7, 0f).fit(null);
        assertNotNull(m);
        float[] far = new float[2], edge = new float[2];
        m.offset(5f, -3f, far);
        m.offset(0.95f, 0.125f, edge);   // 입력이 있던 가장 가까운 곳
        assertEquals(edge[0], far[0], 1e-6f);
        assertEquals(edge[1], far[1], 1e-6f);
    }

    @Test
    public void savesAndLoads() {
        TouchModel m = grid(3, (u, v) -> new float[]{0.01f * u, 0.02f - 0.01f * v * v}, 8, 0.05f).fit(null);
        assertNotNull(m);
        m.rawHitPct = 91;
        m.touchHitPct = 96;
        m.layoutHitPct = 95;
        m.time = 1_700_000_000_000L;
        TouchModel back = TouchModel.parse(m.serialize());
        assertNotNull(back);
        assertEquals(m.samples, back.samples);
        assertEquals(91, back.rawHitPct);
        assertEquals(96, back.touchHitPct);
        assertEquals(95, back.layoutHitPct);
        assertEquals(m.time, back.time);
        assertEquals(m.meanDx, back.meanDx, 1e-5f);
        float[] a = new float[2], b = new float[2];
        for (float u = 0f; u <= 1f; u += 0.25f) {
            m.offset(u, 1 - u, a);
            back.offset(u, 1 - u, b);
            assertEquals(a[0], b[0], 1e-6f);
            assertEquals(a[1], b[1], 1e-6f);
        }
    }

    @Test
    public void ignoresValuesItCannotRead() {
        assertNull(TouchModel.parse(null));
        assertNull(TouchModel.parse(""));
        assertNull(TouchModel.parse("1;2;3"));
        TouchModel m = grid(1, (u, v) -> new float[]{0f, 0f}, 9, 0f).fit(null);
        assertNotNull(m);
        String s = m.serialize();
        assertNull(TouchModel.parse("9" + s.substring(1)));               // 모르는 판
        assertNull(TouchModel.parse(s.replaceFirst(";[^;]*$", ";NaN")));   // 숫자가 아님
        assertTrue(TouchModel.parse(s) != null);
    }
}
