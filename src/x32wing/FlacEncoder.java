package x32wing;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-Java FLAC encoder (1-8 channels, 8-24 bit). Uses fixed and LPC prediction (up to order 8),
 * stereo decorrelation, wasted-bits detection and partitioned Rice coding, roughly matching
 * the compression of the reference encoder at its default level.
 */
public final class FlacEncoder implements Closeable {
    static final int BLOCK = 4096, MAX_LPC = 8, MAX_PART = 8;

    private final Path path;
    private final OutputStream out;
    private final int channels, bps, rate;
    private final int[][] buf;
    private int fill;
    private long total, frameNo;
    private int minFrame = Integer.MAX_VALUE, maxFrame;
    private final MessageDigest md5;
    private final byte[] md5buf;
    private final BitWriter bw;
    private boolean closed;

    // scratch
    private final int[] mid = new int[BLOCK], side = new int[BLOCK], shifted = new int[BLOCK];
    private final int[] res = new int[BLOCK], best = new int[BLOCK];
    private final double[] wdata = new double[BLOCK], win = new double[BLOCK];
    private int winN = -1;
    private final double[] autoc = new double[MAX_LPC + 1], lpErr = new double[MAX_LPC];
    private final double[][] lpCoef = new double[MAX_LPC][MAX_LPC];
    private final int[] qlp = new int[MAX_LPC], bestQlp = new int[MAX_LPC];
    private final long[] psum = new long[1 << MAX_PART];
    private final int[] kTmp = new int[1 << MAX_PART], kPlan = new int[1 << MAX_PART], kBest = new int[1 << MAX_PART];
    private int planP, planCost, bestP, bestShift;

    public FlacEncoder(Path path, int channels, int bps, int rate, String title) throws IOException {
        if (channels < 1 || channels > 8) throw new IOException("FLAC supports 1-8 channels per file");
        if (bps < 4 || bps > 24) throw new IOException("FLAC encoder supports 4-24 bits");
        this.path = path; this.channels = channels; this.bps = bps; this.rate = rate;
        buf = new int[channels][BLOCK];
        bw = new BitWriter(BLOCK * channels * 4 + 4096);
        md5buf = new byte[BLOCK * channels * 4];
        try { md5 = MessageDigest.getInstance("MD5"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
        out.write(new byte[] {'f', 'L', 'a', 'C'});
        out.write(new byte[] {0, 0, 0, 34});
        out.write(new byte[34]); // STREAMINFO, patched on close
        byte[] vendor = "X32-Wing Multitrack Tool".getBytes(StandardCharsets.UTF_8);
        byte[] tag = title == null ? null : ("TITLE=" + title).getBytes(StandardCharsets.UTF_8);
        int len = 4 + vendor.length + 4 + (tag == null ? 0 : 4 + tag.length);
        out.write(new byte[] {4, (byte) (len >> 16), (byte) (len >> 8), (byte) len});
        writeLE32(vendor.length); out.write(vendor);
        writeLE32(tag == null ? 0 : 1);
        if (tag != null) { writeLE32(tag.length); out.write(tag); }
        out.write(new byte[] {(byte) 0x81, 0, 2, 0}); // last block: 512 bytes padding for later tagging
        out.write(new byte[512]);
    }

    private void writeLE32(int v) throws IOException {
        out.write(v); out.write(v >> 8); out.write(v >> 16); out.write(v >> 24);
    }

    /** Appends n frames; src[c] holds samples already scaled to the encoder's bit depth. */
    public void write(int[][] src, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int k = Math.min(BLOCK - fill, n - off);
            for (int c = 0; c < channels; c++) System.arraycopy(src[c], off, buf[c], fill, k);
            fill += k; off += k;
            if (fill == BLOCK) { encodeFrame(BLOCK); fill = 0; }
        }
    }

    private void encodeFrame(int n) throws IOException {
        updateMd5(n);
        bw.reset();
        int bsCode = blockSizeCode(n), srCode = sampleRateCode(rate);
        int chCode = channels - 1;
        int[] ch0 = buf[0], ch1 = channels == 2 ? buf[1] : null;
        int b0 = bps, b1 = bps;
        if (channels == 2 && n > 16) {
            for (int i = 0; i < n; i++) { mid[i] = (ch0[i] + ch1[i]) >> 1; side[i] = ch0[i] - ch1[i]; }
            long eL = estimate(ch0, n, bps), eR = estimate(ch1, n, bps), eM = estimate(mid, n, bps), eS = estimate(side, n, bps + 1);
            long lr = eL + eR, ls = eL + eS, rs = eR + eS, ms = eM + eS;
            long min = Math.min(Math.min(lr, ls), Math.min(rs, ms));
            if (min == ms) { chCode = 10; ch0 = mid; ch1 = side; b1 = bps + 1; }
            else if (min == ls) { chCode = 8; ch1 = side; b1 = bps + 1; }
            else if (min == rs) { chCode = 9; ch0 = side; b0 = bps + 1; ch1 = buf[1]; }
        }
        bw.write(0x3FFE, 14); bw.write(0, 1); bw.write(0, 1);
        bw.write(bsCode, 4); bw.write(srCode, 4); bw.write(chCode, 4); bw.write(sampleSizeCode(bps), 3); bw.write(0, 1);
        writeUtf8(frameNo);
        if (bsCode == 6) bw.write(n - 1, 8); else if (bsCode == 7) bw.write(n - 1, 16);
        bw.write(crc8(bw.buf, bw.len), 8);
        if (channels == 2) { encodeSubframe(ch0, n, b0); encodeSubframe(ch1, n, b1); }
        else for (int c = 0; c < channels; c++) encodeSubframe(buf[c], n, bps);
        bw.align();
        bw.write(crc16(bw.buf, bw.len), 16);
        out.write(bw.buf, 0, bw.len);
        minFrame = Math.min(minFrame, bw.len); maxFrame = Math.max(maxFrame, bw.len);
        frameNo++; total += n;
    }

    private void updateMd5(int n) {
        int bytes = (bps + 7) / 8, p = 0;
        for (int i = 0; i < n; i++)
            for (int c = 0; c < channels; c++) {
                int v = buf[c][i];
                for (int k = 0; k < bytes; k++) md5buf[p++] = (byte) (v >> (8 * k));
            }
        md5.update(md5buf, 0, p);
    }

    /** Cheap bit estimate using the best fixed predictor (used to choose stereo decorrelation). */
    private long estimate(int[] x, int n, int bits) {
        long s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0;
        int x1 = x[3], x2 = x[2], x3 = x[1], x4 = x[0];
        for (int i = 4; i < n; i++) {
            int v = x[i];
            long e0 = v, e1 = e0 - x1, e2 = e1 - (x1 - x2), e3 = e2 - (x1 - 2L * x2 + x3), e4 = e3 - (x1 - 3L * x2 + 3L * x3 - x4);
            s0 += Math.abs(e0); s1 += Math.abs(e1); s2 += Math.abs(e2); s3 += Math.abs(e3); s4 += Math.abs(e4);
            x4 = x3; x3 = x2; x2 = x1; x1 = v;
        }
        long[] s = {s0, s1, s2, s3, s4};
        long bestBits = Long.MAX_VALUE;
        for (int o = 0; o <= 4; o++) bestBits = Math.min(bestBits, o * (long) bits + riceEstimate(2 * s[o], n - o));
        return bestBits;
    }

    private static long riceEstimate(long sumU, int cnt) {
        if (cnt <= 0) return 0;
        int k = bestK(sumU, cnt);
        return (long) cnt * (k + 1) + (sumU >>> k);
    }

    private static int bestK(long sum, int cnt) {
        if (cnt <= 0 || sum <= cnt) return 0;
        long mean = sum / cnt;
        int k = 63 - Long.numberOfLeadingZeros(Math.max(1, mean));
        int bk = 0; long bc = Long.MAX_VALUE;
        for (int t = Math.max(0, k - 1); t <= Math.min(30, k + 1); t++) {
            long c = (long) cnt * (t + 1) + (sum >>> t);
            if (c < bc) { bc = c; bk = t; }
        }
        return bk;
    }

    // ---------------------------------------------------------------------------------------- subframes

    private static final int T_CONST = 0, T_VERB = 1, T_FIXED = 2, T_LPC = 3;

    private void encodeSubframe(int[] x, int n, int bits) {
        boolean constant = true;
        int orBits = 0;
        for (int i = 0; i < n; i++) { orBits |= x[i]; if (x[i] != x[0]) constant = false; }
        if (constant) {
            bw.write(0, 1); bw.write(0, 6); bw.write(0, 1);
            bw.write(x[0], bits);
            return;
        }
        int wasted = Integer.numberOfTrailingZeros(orBits);
        int[] src = x;
        int b = bits;
        if (wasted > 0) {
            for (int i = 0; i < n; i++) shifted[i] = x[i] >> wasted;
            src = shifted; b -= wasted;
        }

        int bestType = T_VERB, bestOrder = 0, bestPrec = 0;
        long bestCost = (long) n * b;
        if (n > 4) {
            int fo = bestFixedOrder(src, n);
            fixedResidual(src, n, fo, res);
            long c = 6 + fo * (long) b + planResidual(res, n, fo);
            if (c < bestCost) { bestCost = c; bestType = T_FIXED; bestOrder = fo; keepPlan(n, fo); }
        }
        if (n > 64 && b > 2) {
            int maxOrder = Math.min(MAX_LPC, n / 4);
            int prec = b <= 16 ? 12 : 14;
            if (computeLpc(src, n, maxOrder)) {
                int o1 = 1, o2 = 1; double e1 = Double.MAX_VALUE, e2 = Double.MAX_VALUE;
                for (int o = 1; o <= maxOrder; o++) {
                    double err = Math.max(lpErr[o - 1], 1e-12);
                    double est = (n - o) * Math.max(0, 0.5 * Math.log(err / n) / Math.log(2)) + o * (b + prec);
                    if (est < e1) { e2 = e1; o2 = o1; e1 = est; o1 = o; } else if (est < e2) { e2 = est; o2 = o; }
                }
                for (int o : o1 == o2 ? new int[] {o1} : new int[] {o1, o2}) {
                    int shift = quantize(lpCoef[o - 1], o, prec);
                    if (shift < 0 || !lpcResidual(src, n, o, shift, res)) continue;
                    long c = 6 + o * (long) b + 4 + 5 + o * (long) prec + planResidual(res, n, o);
                    if (c < bestCost) {
                        bestCost = c; bestType = T_LPC; bestOrder = o; bestPrec = prec; bestShift = shift;
                        System.arraycopy(qlp, 0, bestQlp, 0, o);
                        keepPlan(n, o);
                    }
                }
            }
        }

        bw.write(0, 1);
        switch (bestType) {
            case T_VERB -> bw.write(1, 6);
            case T_FIXED -> bw.write(8 | bestOrder, 6);
            default -> bw.write(32 | (bestOrder - 1), 6);
        }
        if (wasted == 0) bw.write(0, 1);
        else { bw.write(1, 1); bw.writeZeros(wasted - 1); bw.write(1, 1); }
        if (bestType == T_VERB) {
            for (int i = 0; i < n; i++) bw.write(src[i], b);
            return;
        }
        for (int i = 0; i < bestOrder; i++) bw.write(src[i], b);
        if (bestType == T_LPC) {
            bw.write(bestPrec - 1, 4);
            bw.write(bestShift, 5);
            for (int i = 0; i < bestOrder; i++) bw.write(bestQlp[i], bestPrec);
        }
        writeResidual(n, bestOrder);
    }

    private static int bestFixedOrder(int[] x, int n) {
        long s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0;
        int x1 = x[3], x2 = x[2], x3 = x[1], x4 = x[0];
        for (int i = 4; i < n; i++) {
            int v = x[i];
            long e1 = (long) v - x1, e2 = e1 - ((long) x1 - x2), e3 = e2 - ((long) x1 - 2L * x2 + x3), e4 = e3 - ((long) x1 - 3L * x2 + 3L * x3 - x4);
            s0 += Math.abs((long) v); s1 += Math.abs(e1); s2 += Math.abs(e2); s3 += Math.abs(e3); s4 += Math.abs(e4);
            x4 = x3; x3 = x2; x2 = x1; x1 = v;
        }
        long[] s = {s0, s1, s2, s3, s4};
        int o = 0;
        for (int k = 1; k <= 4; k++) if (s[k] < s[o]) o = k;
        return o;
    }

    private static void fixedResidual(int[] x, int n, int order, int[] r) {
        switch (order) {
            case 0 -> System.arraycopy(x, 0, r, 0, n);
            case 1 -> { for (int i = 1; i < n; i++) r[i] = x[i] - x[i - 1]; }
            case 2 -> { for (int i = 2; i < n; i++) r[i] = x[i] - 2 * x[i - 1] + x[i - 2]; }
            case 3 -> { for (int i = 3; i < n; i++) r[i] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]; }
            default -> { for (int i = 4; i < n; i++) r[i] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]; }
        }
    }

    private boolean computeLpc(int[] x, int n, int maxOrder) {
        if (winN != n) { // Tukey(0.5) window
            int taper = n / 4;
            for (int i = 0; i < n; i++) {
                double w = 1;
                if (i < taper) w = 0.5 - 0.5 * Math.cos(Math.PI * i / taper);
                else if (i >= n - taper) w = 0.5 - 0.5 * Math.cos(Math.PI * (n - 1 - i) / taper);
                win[i] = w;
            }
            winN = n;
        }
        for (int i = 0; i < n; i++) wdata[i] = x[i] * win[i];
        for (int lag = 0; lag <= maxOrder; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += wdata[i] * wdata[i - lag];
            autoc[lag] = s;
        }
        if (autoc[0] <= 0) return false;
        double[] lpc = new double[MAX_LPC];
        double err = autoc[0];
        for (int i = 0; i < maxOrder; i++) {
            double r = -autoc[i + 1];
            for (int j = 0; j < i; j++) r -= lpc[j] * autoc[i - j];
            r /= err;
            lpc[i] = r;
            int j;
            for (j = 0; j < (i >> 1); j++) {
                double tmp = lpc[j];
                lpc[j] += r * lpc[i - 1 - j];
                lpc[i - 1 - j] += r * tmp;
            }
            if ((i & 1) != 0) lpc[j] += lpc[j] * r;
            err *= (1.0 - r * r);
            for (j = 0; j <= i; j++) lpCoef[i][j] = -lpc[j];
            lpErr[i] = err;
            if (!(err > 0)) { for (int k = i + 1; k < maxOrder; k++) lpErr[k] = Double.MAX_VALUE; break; }
        }
        return true;
    }

    /** Quantizes coefficients into qlp; returns the shift or -1 if unusable. */
    private int quantize(double[] lp, int order, int precision) {
        double cmax = 0;
        for (int i = 0; i < order; i++) cmax = Math.max(cmax, Math.abs(lp[i]));
        if (!(cmax > 0) || Double.isInfinite(cmax)) return -1;
        int p = precision - 1;
        int qmax = (1 << p) - 1, qmin = -(1 << p);
        int shift = p - Math.getExponent(cmax) - 1;
        if (shift > 15) shift = 15;
        if (shift < 0) return -1;
        double e = 0;
        for (int i = 0; i < order; i++) {
            e += lp[i] * (1 << shift);
            long q = Math.round(e);
            if (q > qmax) q = qmax; else if (q < qmin) q = qmin;
            qlp[i] = (int) q;
            e -= q;
        }
        return shift;
    }

    private boolean lpcResidual(int[] x, int n, int order, int shift, int[] r) {
        final int[] q = qlp;
        for (int i = order; i < n; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) sum += (long) q[j] * x[i - j - 1];
            long e = x[i] - (sum >> shift);
            if (e >= (1L << 30) || e <= -(1L << 30)) return false;
            r[i] = (int) e;
        }
        return true;
    }

    /** Chooses partition order and Rice parameters for res[order..n); returns bit cost (kept in kPlan/planP). */
    private long planResidual(int[] r, int n, int order) {
        int maxP = 0;
        while (maxP < MAX_PART && (n & ((2 << maxP) - 1)) == 0 && (n >> (maxP + 1)) > order) maxP++;
        int parts = 1 << maxP, psize = n >> maxP;
        for (int j = 0; j < parts; j++) {
            long s = 0;
            for (int i = (j == 0 ? order : j * psize), e = (j + 1) * psize; i < e; i++) {
                int v = r[i];
                s += ((v << 1) ^ (v >> 31)) & 0xFFFFFFFFL;
            }
            psum[j] = s;
        }
        long bestCost = Long.MAX_VALUE;
        for (int p = maxP; p >= 0; p--) {
            int np = 1 << p, ps = n >> p;
            long cost = 6;
            boolean rice2 = false;
            for (int j = 0; j < np; j++) {
                int cnt = ps - (j == 0 ? order : 0);
                int k = bestK(psum[j], cnt);
                if (k > 14) rice2 = true;
                kTmp[j] = k;
                cost += (long) cnt * (k + 1) + (psum[j] >>> k);
            }
            cost += (long) np * (rice2 ? 5 : 4);
            if (cost < bestCost) { bestCost = cost; planP = p; System.arraycopy(kTmp, 0, kPlan, 0, np); }
            if (p > 0) for (int j = 0; j < np / 2; j++) psum[j] = psum[2 * j] + psum[2 * j + 1];
        }
        planCost = (int) Math.min(Integer.MAX_VALUE, bestCost);
        return bestCost;
    }

    private void keepPlan(int n, int order) {
        System.arraycopy(res, order, best, order, n - order);
        bestP = planP;
        System.arraycopy(kPlan, 0, kBest, 0, 1 << planP);
    }

    private void writeResidual(int n, int order) {
        int np = 1 << bestP, ps = n >> bestP;
        boolean rice2 = false;
        for (int j = 0; j < np; j++) if (kBest[j] > 14) rice2 = true;
        bw.write(rice2 ? 1 : 0, 2);
        bw.write(bestP, 4);
        int pb = rice2 ? 5 : 4;
        for (int j = 0; j < np; j++) {
            int k = kBest[j];
            bw.write(k, pb);
            for (int i = (j == 0 ? order : j * ps), e = (j + 1) * ps; i < e; i++) {
                int v = best[i];
                bw.writeRice(((v << 1) ^ (v >> 31)) & 0xFFFFFFFFL, k);
            }
        }
    }

    // ---------------------------------------------------------------------------------------- header helpers

    private void writeUtf8(long v) {
        if (v < 0x80) { bw.write(v, 8); return; }
        int bytes = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : v < 0x80000000L ? 6 : 7;
        int lead = (0xFF00 >> bytes) & 0xFF;
        int firstBits = bytes == 7 ? 0 : 7 - bytes;
        bw.write(lead | (v >>> (6 * (bytes - 1))) & ((1 << firstBits) - 1), 8);
        for (int i = bytes - 2; i >= 0; i--) bw.write(0x80 | ((v >>> (6 * i)) & 0x3F), 8);
    }

    private static int blockSizeCode(int n) {
        switch (n) {
            case 192: return 1;
            case 576: return 2; case 1152: return 3; case 2304: return 4; case 4608: return 5;
            case 256: return 8; case 512: return 9; case 1024: return 10; case 2048: return 11;
            case 4096: return 12; case 8192: return 13; case 16384: return 14; case 32768: return 15;
            default: return n <= 256 ? 6 : 7;
        }
    }

    private static int sampleRateCode(int r) {
        return switch (r) {
            case 88200 -> 1; case 176400 -> 2; case 192000 -> 3; case 8000 -> 4; case 16000 -> 5;
            case 22050 -> 6; case 24000 -> 7; case 32000 -> 8; case 44100 -> 9; case 48000 -> 10; case 96000 -> 11;
            default -> 0;
        };
    }

    private static int sampleSizeCode(int b) {
        return switch (b) { case 8 -> 1; case 12 -> 2; case 16 -> 4; case 20 -> 5; case 24 -> 6; default -> 0; };
    }

    private static final int[] CRC8 = new int[256], CRC16 = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
            CRC8[i] = c;
            int d = i << 8;
            for (int k = 0; k < 8; k++) d = (d & 0x8000) != 0 ? ((d << 1) ^ 0x8005) & 0xFFFF : (d << 1) & 0xFFFF;
            CRC16[i] = d;
        }
    }

    static int crc8(byte[] b, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) c = CRC8[(c ^ b[i]) & 0xFF];
        return c;
    }

    static int crc16(byte[] b, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) c = ((c << 8) ^ CRC16[((c >> 8) ^ b[i]) & 0xFF]) & 0xFFFF;
        return c;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (fill > 0) encodeFrame(fill);
        } finally {
            out.close();
        }
        BitWriter si = new BitWriter(64);
        si.write(BLOCK, 16); si.write(BLOCK, 16);
        si.write(total == 0 ? 0 : minFrame, 24); si.write(maxFrame, 24);
        si.write(rate, 20); si.write(channels - 1, 3); si.write(bps - 1, 5);
        si.write(total >>> 32, 4); si.write(total & 0xFFFFFFFFL, 32);
        byte[] digest = md5.digest();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(8);
            raf.write(si.buf, 0, si.len);
            raf.write(digest);
        }
    }

    public long samplesWritten() { return total + fill; }

    /** MSB-first bit writer. */
    static final class BitWriter {
        byte[] buf;
        int len;
        private long acc;
        private int nbits;

        BitWriter(int cap) { buf = new byte[cap]; }

        void reset() { len = 0; acc = 0; nbits = 0; }

        void write(long v, int bits) {
            if (bits == 0) return;
            acc = (acc << bits) | (v & ((1L << bits) - 1));
            nbits += bits;
            if (len + 8 > buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            while (nbits >= 8) { nbits -= 8; buf[len++] = (byte) (acc >>> nbits); }
        }

        void writeZeros(long n) {
            while (n >= 32) { write(0, 32); n -= 32; }
            write(0, (int) n);
        }

        void writeRice(long u, int k) {
            long q = u >>> k;
            if (q + 1 + k <= 32) {
                write((1L << k) | (u & ((1L << k) - 1)), (int) (q + 1 + k));
            } else {
                writeZeros(q);
                write(1, 1);
                write(u, k);
            }
        }

        void align() { if (nbits > 0) write(0, 8 - nbits); }
    }
}
