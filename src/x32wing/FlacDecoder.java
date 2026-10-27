package x32wing;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** General FLAC decoder (all subframe types, both blocking strategies, up to 8 channels / 32 bits). */
public final class FlacDecoder implements Closeable {
    public int channels, bitsPerSample, sampleRate, maxBlockSize;
    public long totalSamples;
    public byte[] md5 = new byte[16];
    public String title;

    private final InputStream in;
    private final byte[] ibuf = new byte[1 << 16];
    private int ipos, ilen;
    private long cache;
    private int cbits;
    private MessageDigest verify;
    private byte[] vbuf = new byte[0];

    public FlacDecoder(Path p) throws IOException {
        in = Files.newInputStream(p);
        try {
            if (readBits(32) != 0x664C6143) throw new IOException("Not a FLAC file: " + p);
            boolean last;
            do {
                last = readBits(1) == 1;
                int type = readBits(7), len = readBits(24);
                if (type == 0) {
                    readBits(16);
                    maxBlockSize = readBits(16);
                    readBits(24); readBits(24);
                    sampleRate = readBits(20);
                    channels = readBits(3) + 1;
                    bitsPerSample = readBits(5) + 1;
                    totalSamples = ((long) readBits(4) << 32) | (readBits(32) & 0xFFFFFFFFL);
                    for (int i = 0; i < 16; i++) md5[i] = (byte) readBits(8);
                    for (int i = 34; i < len; i++) readBits(8);
                } else if (type == 4) {
                    byte[] b = new byte[len];
                    for (int i = 0; i < len; i++) b[i] = (byte) readBits(8);
                    parseVorbis(b);
                } else {
                    for (int i = 0; i < len; i++) readBits(8);
                }
            } while (!last);
            if (channels == 0) throw new IOException("FLAC file has no STREAMINFO: " + p);
            if (maxBlockSize < 16) maxBlockSize = 65535;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    /** Enables MD5 checking of decoded audio; call before the first readFrame. */
    public void enableVerify() {
        try { verify = MessageDigest.getInstance("MD5"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** After full decode with verification on: true if the audio matches the stored MD5 (or none is stored). */
    public boolean md5Matches() {
        boolean none = true;
        for (byte b : md5) if (b != 0) none = false;
        return none || Arrays.equals(md5, verify.digest());
    }

    private void parseVorbis(byte[] b) {
        try {
            int p = 0;
            int vl = le32(b, p); p += 4 + vl;
            int n = le32(b, p); p += 4;
            for (int i = 0; i < n; i++) {
                int l = le32(b, p); p += 4;
                String s = new String(b, p, l, StandardCharsets.UTF_8); p += l;
                if (s.regionMatches(true, 0, "TITLE=", 0, 6)) title = s.substring(6);
            }
        } catch (RuntimeException ignored) { /* malformed tags are not fatal */ }
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
    }

    /** Decodes the next frame into out[c][0..n); returns n, or 0 at end of stream. */
    public int readFrame(int[][] out) throws IOException {
        cbits -= cbits & 7;
        if (cbits == 0 && !fill()) return 0;
        int sync = readBits(14);
        if (sync != 0x3FFE) throw new IOException("FLAC frame sync lost");
        readBits(1); readBits(1);
        int bsCode = readBits(4), srCode = readBits(4), chCode = readBits(4), ssCode = readBits(3);
        readBits(1);
        int first = readBits(8);
        int extra = Integer.numberOfLeadingZeros(~first << 24) - 1;
        for (int i = 0; i < extra; i++) readBits(8);
        int n = switch (bsCode) {
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (bsCode - 2);
            case 6 -> readBits(8) + 1;
            case 7 -> readBits(16) + 1;
            case 0 -> throw new IOException("Reserved FLAC block size");
            default -> 256 << (bsCode - 8);
        };
        if (srCode == 12) readBits(8); else if (srCode == 13 || srCode == 14) readBits(16);
        int bps = switch (ssCode) {
            case 1 -> 8; case 2 -> 12; case 4 -> 16; case 5 -> 20; case 6 -> 24; case 7 -> 32;
            default -> bitsPerSample;
        };
        readBits(8); // CRC-8
        int nch = chCode < 8 ? chCode + 1 : 2;
        if (nch > out.length || n > out[0].length) throw new IOException("FLAC frame larger than buffer");
        for (int c = 0; c < nch; c++) {
            boolean isSide = (chCode == 8 && c == 1) || (chCode == 9 && c == 0) || (chCode == 10 && c == 1);
            decodeSubframe(out[c], n, bps + (isSide ? 1 : 0));
        }
        cbits -= cbits & 7;
        readBits(16); // CRC-16
        int[] a = out[0], b = nch > 1 ? out[1] : null;
        switch (chCode) {
            case 8 -> { for (int i = 0; i < n; i++) b[i] = a[i] - b[i]; }
            case 9 -> { for (int i = 0; i < n; i++) a[i] += b[i]; }
            case 10 -> {
                for (int i = 0; i < n; i++) {
                    int m = (a[i] << 1) | (b[i] & 1), s = b[i];
                    a[i] = (m + s) >> 1; b[i] = (m - s) >> 1;
                }
            }
            default -> { }
        }
        if (verify != null) {
            int bytes = (bps + 7) / 8;
            if (vbuf.length < n * nch * bytes) vbuf = new byte[n * nch * bytes];
            int p = 0;
            for (int i = 0; i < n; i++)
                for (int c = 0; c < nch; c++) {
                    int v = out[c][i];
                    for (int k = 0; k < bytes; k++) vbuf[p++] = (byte) (v >> (8 * k));
                }
            verify.update(vbuf, 0, p);
        }
        return n;
    }

    private void decodeSubframe(int[] x, int n, int bits) throws IOException {
        readBits(1);
        int type = readBits(6);
        int wasted = 0;
        if (readBits(1) == 1) wasted = readUnary() + 1;
        bits -= wasted;
        if (type == 0) {
            Arrays.fill(x, 0, n, readSigned(bits));
        } else if (type == 1) {
            for (int i = 0; i < n; i++) x[i] = readSigned(bits);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            for (int i = 0; i < order; i++) x[i] = readSigned(bits);
            residual(x, n, order);
            switch (order) {
                case 1 -> { for (int i = 1; i < n; i++) x[i] += x[i - 1]; }
                case 2 -> { for (int i = 2; i < n; i++) x[i] += 2 * x[i - 1] - x[i - 2]; }
                case 3 -> { for (int i = 3; i < n; i++) x[i] += 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]; }
                case 4 -> { for (int i = 4; i < n; i++) x[i] += 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]; }
                default -> { }
            }
        } else if (type >= 32) {
            int order = type - 31;
            for (int i = 0; i < order; i++) x[i] = readSigned(bits);
            int prec = readBits(4) + 1;
            int shift = readSigned(5);
            int[] q = new int[order];
            for (int i = 0; i < order; i++) q[i] = readSigned(prec);
            residual(x, n, order);
            for (int i = order; i < n; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += (long) q[j] * x[i - j - 1];
                x[i] += (int) (sum >> shift);
            }
        } else {
            throw new IOException("Reserved FLAC subframe type " + type);
        }
        if (wasted > 0) for (int i = 0; i < n; i++) x[i] <<= wasted;
    }

    private void residual(int[] x, int n, int order) throws IOException {
        int method = readBits(2);
        int pb = method == 0 ? 4 : 5, esc = method == 0 ? 15 : 31;
        int p = readBits(4), np = 1 << p, ps = n >> p, i = order;
        for (int j = 0; j < np; j++) {
            int cnt = ps - (j == 0 ? order : 0);
            int k = readBits(pb);
            if (k == esc) {
                int nb = readBits(5);
                for (int t = 0; t < cnt; t++) x[i++] = nb == 0 ? 0 : readSigned(nb);
            } else {
                for (int t = 0; t < cnt; t++) {
                    long u = ((long) readUnary() << k) | (k == 0 ? 0 : readBits(k) & 0xFFFFFFFFL);
                    x[i++] = (int) ((u >>> 1) ^ -(u & 1));
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------- bit reading

    private boolean fill() throws IOException {
        if (ipos >= ilen) {
            ilen = in.read(ibuf);
            ipos = 0;
            if (ilen <= 0) { ilen = 0; return false; }
        }
        cache = (cache << 8) | (ibuf[ipos++] & 0xFF);
        cbits += 8;
        return true;
    }

    private int readBits(int n) throws IOException {
        if (n == 0) return 0;
        while (cbits < n) if (!fill()) throw new EOFException("Unexpected end of FLAC file");
        cbits -= n;
        return (int) ((cache >>> cbits) & ((1L << n) - 1));
    }

    private int readSigned(int n) throws IOException {
        if (n == 0) return 0;
        int v = readBits(n);
        return n == 32 ? v : (v << (32 - n)) >> (32 - n);
    }

    private int readUnary() throws IOException {
        int count = 0;
        while (true) {
            if (cbits == 0 && !fill()) throw new EOFException("Unexpected end of FLAC file");
            long bits = cache & ((1L << cbits) - 1);
            if (bits == 0) { count += cbits; cbits = 0; continue; }
            int top = 63 - Long.numberOfLeadingZeros(bits);
            count += cbits - 1 - top;
            cbits = top;
            return count;
        }
    }

    @Override public void close() throws IOException { in.close(); }
}
