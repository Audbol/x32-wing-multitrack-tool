package x32wing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * X-Live / WING-LIVE session log (SE_LOG.BIN), 2048 bytes little-endian:
 * <pre>
 *   0  session id (FAT packed date/time, also the folder name in hex)
 *   4  channels          8  sample rate       12  session id (repeat)
 *  16  number of takes  20  number of markers 24  total length (frames)
 *  28  take sizes [256] (32-bit words per take, i.e. frames * channels)
 * 1052 markers [125]    (sample positions)
 * 1552 session name (16 bytes, zero terminated)
 * </pre>
 */
public final class SeLog {
    public static final int SIZE = 2048, MAX_TAKES = 256, MAX_MARKERS = 125, NAME_OFF = 1552;

    public int sessionId, channels, sampleRate, numTakes;
    public long totalFrames;
    public long[] takeSizes = new long[0];
    public final List<Long> markers = new ArrayList<>();
    public String name = "";

    public static SeLog read(Path p) throws IOException {
        byte[] data = Files.readAllBytes(p);
        if (data.length < NAME_OFF + 16) throw new IOException("SE_LOG.BIN too short (" + data.length + " bytes)");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        SeLog l = new SeLog();
        l.sessionId = b.getInt(0);
        l.channels = b.getInt(4);
        l.sampleRate = b.getInt(8);
        l.numTakes = Math.max(0, Math.min(MAX_TAKES, b.getInt(16)));
        int nm = Math.max(0, Math.min(MAX_MARKERS, b.getInt(20)));
        l.totalFrames = b.getInt(24) & 0xFFFFFFFFL;
        if (l.channels < 1 || l.channels > 64 || l.sampleRate < 8000 || l.sampleRate > 192000)
            throw new IOException("SE_LOG.BIN does not look valid (ch=" + l.channels + ", rate=" + l.sampleRate + ")");
        l.takeSizes = new long[l.numTakes];
        for (int i = 0; i < l.numTakes; i++) l.takeSizes[i] = b.getInt(28 + 4 * i) & 0xFFFFFFFFL;
        for (int i = 0; i < nm; i++) l.markers.add(b.getInt(28 + 4 * MAX_TAKES + 4 * i) & 0xFFFFFFFFL);
        int end = NAME_OFF;
        while (end < NAME_OFF + 16 && data[end] != 0) end++;
        l.name = new String(data, NAME_OFF, end - NAME_OFF, StandardCharsets.ISO_8859_1).trim();
        return l;
    }

    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, sessionId).putInt(4, channels).putInt(8, sampleRate).putInt(12, sessionId);
        b.putInt(16, takeSizes.length);
        int nm = Math.min(MAX_MARKERS, markers.size());
        b.putInt(20, nm).putInt(24, (int) totalFrames);
        for (int i = 0; i < takeSizes.length && i < MAX_TAKES; i++) b.putInt(28 + 4 * i, (int) takeSizes[i]);
        for (int i = 0; i < nm; i++) b.putInt(28 + 4 * MAX_TAKES + 4 * i, (int) (long) markers.get(i));
        byte[] nb = name.getBytes(StandardCharsets.ISO_8859_1);
        b.put(NAME_OFF, nb, 0, Math.min(15, nb.length));
        return b.array();
    }

    /** Writes the log, keeping a one-time backup (SE_LOG.BIN.bak) of the original. */
    public void write(Path p) throws IOException {
        Path bak = p.resolveSibling(p.getFileName() + ".bak");
        if (Files.exists(p) && !Files.exists(bak)) Files.copy(p, bak, StandardCopyOption.COPY_ATTRIBUTES);
        Files.write(p, toBytes());
    }

    /** FAT-style packed date/time used for session ids and folder names. */
    public static int packDate(LocalDateTime t) {
        return ((t.getYear() - 1980) << 25) | (t.getMonthValue() << 21) | (t.getDayOfMonth() << 16)
                | (t.getHour() << 11) | (t.getMinute() << 5) | (t.getSecond() / 2);
    }

    public static LocalDateTime unpackDate(int v) {
        try {
            int y = 1980 + (v >>> 25), mo = (v >>> 21) & 15, d = (v >>> 16) & 31, h = (v >>> 11) & 31, mi = (v >>> 5) & 63, s = (v & 31) * 2;
            if (y > 2100) return null;
            return LocalDateTime.of(y, mo, d, h, mi, Math.min(59, s));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
