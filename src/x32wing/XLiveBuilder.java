package x32wing;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an X-Live / WING-LIVE compatible session (for Virtual Soundcheck playback from the SD card)
 * out of mono/stereo/multichannel WAV or FLAC files.
 */
public final class XLiveBuilder {
    /** Max audio bytes per take, as the card writes (4 GB minus 64 KB). */
    public static final long MAX_TAKE_BYTES = 0xFFFF0000L;
    private static final int HEADER = 32768;

    public long maxTakeBytes = MAX_TAKE_BYTES;
    public int maxChannels = 32;

    public Path build(List<Path> inputs, Path outRoot, String name, List<Double> markerSeconds, ExportJob.Progress progress) throws IOException {
        if (inputs.isEmpty()) throw new IOException("No input files.");
        List<AudioSource> srcs = new ArrayList<>();
        try {
            for (Path p : inputs) srcs.add(AudioSource.openFile(p));
            int rate = srcs.get(0).sampleRate();
            int used = 0;
            for (int i = 0; i < srcs.size(); i++) {
                if (srcs.get(i).sampleRate() != rate)
                    throw new IOException(inputs.get(i).getFileName() + " is " + srcs.get(i).sampleRate() + " Hz but the first file is " + rate + " Hz. All files must share one sample rate.");
                used += srcs.get(i).channels();
            }
            if (rate != 44100 && rate != 48000) throw new IOException("The card only plays 44.1 or 48 kHz sessions (files are " + rate + " Hz).");
            if (used > maxChannels) throw new IOException("Inputs have " + used + " channels; a card session holds at most " + maxChannels + ".");
            int nch = used <= 8 ? 8 : used <= 16 ? 16 : 32;
            if (nch > maxChannels) nch = maxChannels;

            AudioSource all = new AudioSource.Concat(srcs);
            long frames = all.frames();
            int frameBytes = nch * 4;
            long framesPerTake = maxTakeBytes / frameBytes;

            LocalDateTime now = LocalDateTime.now();
            int id = SeLog.packDate(now);
            Path base = outRoot.getFileName() != null && outRoot.getFileName().toString().equalsIgnoreCase("X_LIVE") ? outRoot : outRoot.resolve("X_LIVE");
            String hex = String.format("%08X", id);
            Path dir = base.resolve(hex);
            while (Files.exists(dir)) { id += 1; hex = String.format("%08X", id); dir = base.resolve(hex); }
            Files.createDirectories(dir);

            List<Long> takeSizes = new ArrayList<>();
            int[][] block = new int[used][ExportJob.READ_BLOCK];
            ByteBuffer out = ByteBuffer.allocate(ExportJob.READ_BLOCK * frameBytes).order(ByteOrder.LITTLE_ENDIAN);
            all.seek(0);
            long written = 0;
            int take = 0;
            while (written < frames || take == 0) {
                take++;
                long takeFrames = Math.min(framesPerTake, frames - written);
                Path f = dir.resolve(String.format("%08X.WAV", take)); // the card numbers takes in hex
                try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writeFully(ch, header(nch, rate, takeFrames * frameBytes));
                    long left = takeFrames;
                    while (left > 0) {
                        if (progress != null && progress.cancelled()) throw new InterruptedIOException("Cancelled");
                        int n = all.read(block, 0, (int) Math.min(ExportJob.READ_BLOCK, left));
                        if (n <= 0) throw new IOException("Input ended early");
                        out.clear();
                        for (int i = 0; i < n; i++) {
                            for (int c = 0; c < used; c++) out.putInt(block[c][i]);
                            for (int c = used; c < nch; c++) out.putInt(0);
                        }
                        out.flip();
                        writeFully(ch, out);
                        left -= n; written += n;
                        if (progress != null) progress.update("Building card session " + hex, written / (double) Math.max(1, frames), "take " + take);
                    }
                }
                takeSizes.add(takeFrames * nch);
                if (frames == 0) break;
            }

            SeLog log = new SeLog();
            log.sessionId = id; log.channels = nch; log.sampleRate = rate; log.totalFrames = frames;
            log.takeSizes = takeSizes.stream().mapToLong(Long::longValue).toArray();
            log.name = name == null ? "" : name;
            if (markerSeconds != null)
                for (double s : markerSeconds) {
                    long m = Math.round(s * rate);
                    if (m > 0 && m < frames && log.markers.size() < 100) log.markers.add(m);
                }
            log.markers.sort(null);
            Files.write(dir.resolve("SE_LOG.BIN"), log.toBytes());
            if (progress != null) progress.update("Done", 1, "");
            return dir;
        } finally {
            for (AudioSource s : srcs) try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** 32 KB header laid out like the card's own files: RIFF + fmt + JUNK padding, audio starts at 0x8000. */
    static ByteBuffer header(int nch, int rate, long dataBytes) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt((int) (dataBytes + HEADER - 8)).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) nch).putInt(rate)
                .putInt(rate * nch * 4).putShort((short) (nch * 4)).putShort((short) 32);
        h.put("JUNK".getBytes()).putInt(HEADER - 52);
        while (h.position() < 504) h.put((byte) ' ');
        h.put("data".getBytes()).putInt((int) dataBytes);
        while (h.position() < HEADER - 8) h.put((byte) ' ');
        h.put("data".getBytes()).putInt((int) dataBytes);
        h.flip();
        return h;
    }

    private static void writeFully(FileChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) ch.write(b);
    }
}
