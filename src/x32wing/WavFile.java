package x32wing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Parsed header of a WAV / RF64 / BW64 file. Tolerant of unfinalized recordings
 * (card pulled or power lost mid-take) where the data size field is 0 or bogus.
 */
public final class WavFile {
    public final Path path;
    public final int channels, sampleRate, bitsPerSample, blockAlign;
    public final boolean isFloat;
    public final long fileSize, dataOffset, dataBytes;
    /** True when the header's data size was invalid and was recomputed from the file size. */
    public final boolean headerBroken;
    final long riffSizeFieldPos, dataSizeFieldPos;
    final boolean rf64;

    private WavFile(Path path, int channels, int sampleRate, int bits, int blockAlign, boolean isFloat,
                    long fileSize, long dataOffset, long dataBytes, boolean headerBroken,
                    long riffSizePos, long dataSizePos, boolean rf64) {
        this.path = path; this.channels = channels; this.sampleRate = sampleRate; this.bitsPerSample = bits;
        this.blockAlign = blockAlign; this.isFloat = isFloat; this.fileSize = fileSize; this.dataOffset = dataOffset;
        this.dataBytes = dataBytes; this.headerBroken = headerBroken; this.riffSizeFieldPos = riffSizePos;
        this.dataSizeFieldPos = dataSizePos; this.rf64 = rf64;
    }

    public long frames() { return dataBytes / blockAlign; }

    public String formatLabel() { return (isFloat ? bitsPerSample + "-bit float" : bitsPerSample + "-bit"); }

    public static WavFile open(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            long size = ch.size();
            ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            if (readAt(ch, b, 0, 12) < 12) throw new IOException("Not a WAV file (too short): " + p);
            String riff = id(b, 0), wave = id(b, 8);
            boolean rf64 = riff.equals("RF64") || riff.equals("BW64");
            if (!(riff.equals("RIFF") || rf64) || !wave.equals("WAVE")) throw new IOException("Not a WAV file: " + p);

            int fmtTag = -1, channels = 0, rate = 0, bits = 0, align = 0;
            long ds64Data = -1, pos = 12;
            while (pos + 8 <= size) {
                readAt(ch, b, pos, 8);
                String cid = id(b, 0);
                long csize = b.getInt(4) & 0xFFFFFFFFL;
                if (cid.equals("fmt ")) {
                    readAt(ch, b, pos + 8, (int) Math.min(40, csize));
                    fmtTag = b.getShort(0) & 0xFFFF;
                    channels = b.getShort(2) & 0xFFFF;
                    rate = b.getInt(4);
                    align = b.getShort(12) & 0xFFFF;
                    bits = b.getShort(14) & 0xFFFF;
                    if (fmtTag == 0xFFFE && csize >= 40) fmtTag = b.getShort(24) & 0xFFFF; // sub-format GUID
                } else if (cid.equals("ds64")) {
                    readAt(ch, b, pos + 8, 16);
                    ds64Data = b.getLong(8);
                } else if (cid.equals("data")) {
                    if (fmtTag < 0) throw new IOException("WAV has no fmt chunk before data: " + p);
                    if (fmtTag != 1 && fmtTag != 3) throw new IOException("Unsupported WAV encoding (tag " + fmtTag + "): " + p);
                    if (channels <= 0 || align <= 0 || !(bits == 8 || bits == 16 || bits == 24 || bits == 32 || (bits == 64 && fmtTag == 3)))
                        throw new IOException("Unsupported WAV format (" + bits + " bit, " + channels + " ch): " + p);
                    long off = pos + 8, avail = size - off, declared = csize;
                    if (rf64 && csize == 0xFFFFFFFFL && ds64Data >= 0) declared = ds64Data;
                    boolean broken = declared == 0 || declared > avail || (!rf64 && csize == 0xFFFFFFFFL);
                    long bytes = broken ? avail : declared;
                    bytes -= bytes % align;
                    return new WavFile(p, channels, rate, bits, align, fmtTag == 3, size, off, bytes, broken, 4, pos + 4, rf64);
                }
                long next = pos + 8 + csize + (csize & 1);
                if (next <= pos) break;
                pos = next;
            }
            throw new IOException("WAV has no data chunk: " + p);
        }
    }

    /** Rewrites RIFF/data sizes of an unfinalized file in place so other software can read it. */
    public void repairHeader() throws IOException {
        if (!headerBroken || rf64) return;
        if (dataOffset + dataBytes > 0xFFFFFFFFL) throw new IOException("File too large for plain WAV: " + path);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0, (int) (dataOffset + dataBytes - 8));
            ch.write(b, riffSizeFieldPos);
            b.clear(); b.putInt(0, (int) dataBytes);
            ch.write(b, dataSizeFieldPos);
        }
    }

    private static int readAt(FileChannel ch, ByteBuffer b, long pos, int n) throws IOException {
        b.clear().limit(n);
        int total = 0;
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static String id(ByteBuffer b, int off) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b.get(off + i) & 0xFF);
        return new String(c);
    }

    /** Sequential reader producing samples as 32-bit left-justified ints. */
    public Reader reader() throws IOException { return new Reader(); }

    public final class Reader implements AutoCloseable {
        private final FileChannel ch;
        private final ByteBuffer buf;
        private long frame;

        Reader() throws IOException {
            ch = FileChannel.open(path, StandardOpenOption.READ);
            buf = ByteBuffer.allocate(Math.max(blockAlign, (1 << 20) / blockAlign * blockAlign)).order(ByteOrder.LITTLE_ENDIAN);
        }

        public void seek(long f) { frame = Math.max(0, Math.min(f, frames())); }

        /** Reads up to max frames into dst[chOff + c][off + i]. Returns frames read; 0 at end. */
        public int read(int[][] dst, int chOff, int off, int max) throws IOException {
            int want = (int) Math.min(max, frames() - frame), done = 0;
            while (done < want) {
                int n = Math.min(want - done, buf.capacity() / blockAlign);
                buf.clear().limit(n * blockAlign);
                long pos = dataOffset + frame * blockAlign;
                while (buf.hasRemaining()) {
                    int r = ch.read(buf, pos + buf.position());
                    if (r < 0) break;
                }
                n = buf.position() / blockAlign;
                if (n == 0) break;
                decode(dst, chOff, off + done, n);
                frame += n;
                done += n;
            }
            return done;
        }

        private void decode(int[][] dst, int chOff, int off, int n) {
            ByteBuffer b = buf;
            int nc = channels, bps = bitsPerSample / 8;
            for (int c = 0; c < nc; c++) {
                int[] d = dst[chOff + c];
                int p = c * bps;
                if (isFloat && bitsPerSample == 32) {
                    for (int i = 0; i < n; i++, p += blockAlign) d[off + i] = floatToInt(b.getFloat(p));
                } else if (isFloat) {
                    for (int i = 0; i < n; i++, p += blockAlign) d[off + i] = floatToInt(b.getDouble(p));
                } else switch (bitsPerSample) {
                    case 32 -> { for (int i = 0; i < n; i++, p += blockAlign) d[off + i] = b.getInt(p); }
                    case 24 -> { for (int i = 0; i < n; i++, p += blockAlign)
                        d[off + i] = ((b.get(p) & 0xFF) << 8) | ((b.get(p + 1) & 0xFF) << 16) | (b.get(p + 2) << 24); }
                    case 16 -> { for (int i = 0; i < n; i++, p += blockAlign) d[off + i] = b.getShort(p) << 16; }
                    default -> { for (int i = 0; i < n; i++, p += blockAlign) d[off + i] = ((b.get(p) & 0xFF) - 128) << 24; }
                }
            }
        }

        @Override public void close() throws IOException { ch.close(); }
    }

    static int floatToInt(double v) {
        double s = v * 2147483648.0;
        if (s >= 2147483647.0) return Integer.MAX_VALUE;
        if (s <= -2147483648.0) return Integer.MIN_VALUE;
        return (int) Math.round(s);
    }
}
