package x32wing;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Streaming WAV writer (16/24/32-bit int or 32-bit float). Switches to RF64 automatically past 4 GB. */
public final class WavWriter implements Closeable {
    private static final int HEADER = 80; // RIFF(12) + JUNK/ds64(36) + fmt(8+16) + data(8)
    private final FileChannel ch;
    private final int channels, bits, bytesPerSample;
    private final boolean isFloat;
    private final ByteBuffer buf = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
    private long dataBytes;

    /** For float output, samples passed to write() are raw float bits (Float.floatToRawIntBits). */
    public WavWriter(Path p, int channels, int rate, int bits, boolean isFloat) throws IOException {
        this.channels = channels; this.bits = bits; this.isFloat = isFloat; this.bytesPerSample = bits / 8;
        ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(0).put("WAVE".getBytes());
        h.put("JUNK".getBytes()).putInt(28).put(new byte[28]);
        h.put("fmt ".getBytes()).putInt(16).putShort((short) (isFloat ? 3 : 1)).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * bytesPerSample).putShort((short) (channels * bytesPerSample)).putShort((short) bits);
        h.put("data".getBytes()).putInt(0);
        h.flip();
        while (h.hasRemaining()) ch.write(h);
    }

    public void write(int[][] data, int n) throws IOException {
        int frameBytes = channels * bytesPerSample;
        for (int i = 0; i < n; i++) {
            if (buf.remaining() < frameBytes) flush();
            for (int c = 0; c < channels; c++) {
                int v = data[c][i];
                switch (bytesPerSample) {
                    case 2 -> buf.putShort((short) v);
                    case 3 -> { buf.put((byte) v); buf.put((byte) (v >> 8)); buf.put((byte) (v >> 16)); }
                    default -> buf.putInt(v);
                }
            }
        }
        dataBytes += (long) n * frameBytes;
    }

    private void flush() throws IOException {
        buf.flip();
        while (buf.hasRemaining()) ch.write(buf);
        buf.clear();
    }

    @Override
    public void close() throws IOException {
        try {
            flush();
            if ((dataBytes & 1) == 1) ch.write(ByteBuffer.wrap(new byte[1]));
            ByteBuffer h = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            long riff = HEADER - 8 + dataBytes + (dataBytes & 1);
            if (riff <= 0xFFFFFFFFL) {
                ch.write(h.putInt(0, (int) riff), 4);
                h.clear();
                ch.write(h.putInt(0, (int) dataBytes), HEADER - 4);
            } else {
                ByteBuffer d = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
                d.put("ds64".getBytes()).putInt(28).putLong(riff).putLong(dataBytes)
                        .putLong(dataBytes / (channels * bytesPerSample)).putInt(0).flip();
                ch.write(d, 12);
                ch.write(ByteBuffer.wrap("RF64".getBytes()), 0);
                ch.write(ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}), 4);
                ch.write(ByteBuffer.wrap(new byte[] {-1, -1, -1, -1}), HEADER - 4);
            }
        } finally {
            ch.close();
        }
    }
}
