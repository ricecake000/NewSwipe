package com.alternative_studios.newswipe.keyboard;

import java.util.Locale;

/**
 * 터치 보정 결과: 어떤 키를 누르려 할 때 손가락이 키 가운데에서 얼마나 비켜 닿는지를 자판 위치에 따라 나타낸 매끄러운 값.
 *
 * <p>위치와 비켜 닿는 정도는 모두 자판 안쪽(둘레 여백을 뺀 곳)의 폭·높이를 1로 잰다 (u: 왼쪽 0 ~ 오른쪽 1, v: 위 0 ~ 아래 1).
 * 그래서 한글·영어·기호 자판처럼 키 배치가 달라도 같은 값을 쓸 수 있다. 비켜 닿는 정도는 가로·세로 따로
 * 위치의 2차식(1, u, v, u², uv, v²)으로 맞추고, 크게 어긋난 입력(문장과 다른 키를 친 것)은 빼고 다시 맞춘다.
 */
public final class TouchModel {
    private static final int VERSION = 1;
    private static final int TERMS = 6;
    /** 이보다 입력이 적으면 결과를 믿을 수 없어 저장하지 않는다. */
    public static final int MIN_SAMPLES = 30;
    /** 비켜 닿는 정도의 한계 (자판 폭·높이에 대한 비율). 손가락이 이보다 멀리 닿는다고 보지 않는다. */
    private static final float MAX_U = 0.1f, MAX_V = 0.2f;
    /** 처음부터 빼는 입력: 누르려던 키에서 키 폭의 1.5배, 키 높이의 1.2배보다 멀리 닿았다 (문장의 다른 글자를 쳤다). */
    private static final float GROSS_X = 1.5f, GROSS_Y = 1.2f;
    /** 한 번 맞춘 뒤 빼는 입력: 맞춘 값에서 키 크기의 0.75배보다 멀리 떨어졌다. */
    private static final float RESIDUAL = 0.75f;
    /** 항마다 계수를 0 쪽으로 당기는 세기 (상수항은 당기지 않는다). 입력이 고르게 퍼지지 않아도 값이 튀지 않게 한다. */
    private static final double[] RIDGE = {0, 1, 1, 2, 2, 2};

    private final float[] cu = new float[TERMS], cv = new float[TERMS];
    /** 보정에 쓴 입력이 놓인 범위. 이 밖은 가장자리 값을 그대로 쓴다 (2차식이 멀리서 커지지 않게). */
    private float minU, maxU, minV, maxV;

    /** 보정에 쓴 입력 수. */
    public int samples;
    /** 보정에 쓴 입력이 누르려던 키에 닿은 비율(%): 보정 없이, 터치 위치 보정으로, 자판 모양 보정으로. */
    public int rawHitPct, touchHitPct, layoutHitPct;
    /** 평균적으로 비켜 닿은 정도 (키 폭·높이 단위, 오른쪽·아래가 +). */
    public float meanDx, meanDy;
    /** 보정한 때 (System.currentTimeMillis). */
    public long time;

    private TouchModel() {
    }

    // ---------------------------------------------------------------- 계산

    /** (u, v)를 누르려 할 때 손가락이 닿는 곳까지의 차이 (out[0]: 가로, out[1]: 세로, 자판 크기에 대한 비율). */
    public void offset(float u, float v, float[] out) {
        double a = 2 * clamp(u, minU, maxU) - 1, b = 2 * clamp(v, minV, maxV) - 1;
        double[] f = {1, a, b, a * a, a * b, b * b};
        double ou = 0, ov = 0;
        for (int i = 0; i < TERMS; i++) {
            ou += cu[i] * f[i];
            ov += cv[i] * f[i];
        }
        out[0] = clamp((float) ou, -MAX_U, MAX_U);
        out[1] = clamp((float) ov, -MAX_V, MAX_V);
    }

    /** 손가락이 (u, v)에 닿았을 때 누르려던 곳 (out[0]·out[1]). 닿는 곳 = 누르려던 곳 + offset을 거꾸로 푼다. */
    public void target(float u, float v, float[] out) {
        float tu = u, tv = v;
        for (int i = 0; i < 6; i++) {
            offset(tu, tv, out);
            tu = u - out[0];
            tv = v - out[1];
        }
        out[0] = tu;
        out[1] = tv;
    }

    /**
     * 입력에서 값을 맞춘다. 배열은 모두 길이 n이고 위치·차이는 자판 크기에 대한 비율, 키 크기도 같은 단위다:
     * tu·tv는 누르려던 키의 가운데, du·dv는 손가락이 닿은 곳에서 그 가운데를 뺀 것, kw·kh는 누르려던 키의 폭·높이.
     *
     * @param used (나올 때) 입력마다 결과에 썼는지. 필요 없으면 null.
     * @return 쓸 만한 입력이 MIN_SAMPLES보다 적거나 절반도 안 되면 null.
     */
    public static TouchModel fit(float[] tu, float[] tv, float[] du, float[] dv, float[] kw, float[] kh, int n,
                                 boolean[] used) {
        boolean[] keep = used != null ? used : new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            keep[i] = Math.abs(du[i]) <= GROSS_X * kw[i] && Math.abs(dv[i]) <= GROSS_Y * kh[i];
            if (keep[i]) count++;
        }
        if (!enough(count, n)) return null;
        TouchModel m = new TouchModel();
        m.solve(tu, tv, du, dv, keep, n);
        // 맞춘 값에서 크게 벗어난 입력을 빼고 한 번 더 맞춘다.
        float[] o = new float[2];
        count = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            m.offset(tu[i], tv[i], o);
            keep[i] = Math.abs(du[i] - o[0]) <= RESIDUAL * kw[i] && Math.abs(dv[i] - o[1]) <= RESIDUAL * kh[i];
            if (keep[i]) count++;
        }
        if (!enough(count, n)) return null;
        m.solve(tu, tv, du, dv, keep, n);
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            sx += du[i] / kw[i];
            sy += dv[i] / kh[i];
        }
        m.samples = count;
        m.meanDx = (float) (sx / count);
        m.meanDy = (float) (sy / count);
        return m;
    }

    private static boolean enough(int count, int n) {
        return count >= MIN_SAMPLES && count * 2 >= n;
    }

    private void solve(float[] tu, float[] tv, float[] du, float[] dv, boolean[] keep, int n) {
        minU = minV = 1;
        maxU = maxV = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            minU = Math.min(minU, tu[i]);
            maxU = Math.max(maxU, tu[i]);
            minV = Math.min(minV, tv[i]);
            maxV = Math.max(maxV, tv[i]);
        }
        double[][] ata = new double[TERMS][TERMS];
        double[] atu = new double[TERMS], atv = new double[TERMS];
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            double a = 2 * tu[i] - 1, b = 2 * tv[i] - 1;
            double[] f = {1, a, b, a * a, a * b, b * b};
            for (int r = 0; r < TERMS; r++) {
                for (int c = 0; c < TERMS; c++) ata[r][c] += f[r] * f[c];
                atu[r] += f[r] * du[i];
                atv[r] += f[r] * dv[i];
            }
        }
        for (int r = 0; r < TERMS; r++) ata[r][r] += RIDGE[r];
        double[] xu = gauss(copy(ata), atu), xv = gauss(copy(ata), atv);
        for (int i = 0; i < TERMS; i++) {
            cu[i] = (float) xu[i];
            cv[i] = (float) xv[i];
        }
    }

    private static double[][] copy(double[][] m) {
        double[][] out = new double[m.length][];
        for (int i = 0; i < m.length; i++) out[i] = m[i].clone();
        return out;
    }

    /** a·x = b를 푼다 (부분 피벗 가우스 소거). 풀 수 없는 항은 0으로 둔다. */
    private static double[] gauss(double[][] a, double[] b) {
        int n = b.length;
        double[] x = b.clone();
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            double[] t = a[col];
            a[col] = a[pivot];
            a[pivot] = t;
            double tx = x[col];
            x[col] = x[pivot];
            x[pivot] = tx;
            if (Math.abs(a[col][col]) < 1e-12) continue;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double k = a[r][col] / a[col][col];
                if (k == 0) continue;
                for (int c = col; c < n; c++) a[r][c] -= k * a[col][c];
                x[r] -= k * x[col];
            }
        }
        for (int i = 0; i < n; i++) x[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : x[i] / a[i][i];
        return x;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------- 저장

    /** 설정에 저장할 한 줄. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(VERSION).append(';').append(samples).append(';').append(rawHitPct).append(';').append(touchHitPct)
                .append(';').append(layoutHitPct).append(';').append(time);
        for (float f : new float[]{meanDx, meanDy, minU, maxU, minV, maxV}) sb.append(';').append(num(f));
        for (float f : cu) sb.append(';').append(num(f));
        for (float f : cv) sb.append(';').append(num(f));
        return sb.toString();
    }

    private static String num(float f) {
        return String.format(Locale.ROOT, "%.6g", f);
    }

    /** serialize()로 만든 값을 읽는다. 알아볼 수 없으면 null. */
    public static TouchModel parse(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length != 6 + 6 + 2 * TERMS) return null;
        try {
            if (Integer.parseInt(p[0]) != VERSION) return null;
            TouchModel m = new TouchModel();
            m.samples = Integer.parseInt(p[1]);
            m.rawHitPct = Integer.parseInt(p[2]);
            m.touchHitPct = Integer.parseInt(p[3]);
            m.layoutHitPct = Integer.parseInt(p[4]);
            m.time = Long.parseLong(p[5]);
            float[] f = new float[p.length - 6];
            for (int i = 0; i < f.length; i++) {
                f[i] = Float.parseFloat(p[6 + i]);
                if (Float.isNaN(f[i]) || Float.isInfinite(f[i])) return null;
            }
            m.meanDx = f[0];
            m.meanDy = f[1];
            m.minU = f[2];
            m.maxU = f[3];
            m.minV = f[4];
            m.maxV = f[5];
            System.arraycopy(f, 6, m.cu, 0, TERMS);
            System.arraycopy(f, 6 + TERMS, m.cv, 0, TERMS);
            return m;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
