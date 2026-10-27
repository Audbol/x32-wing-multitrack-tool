package x32wing;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Splits a multitrack recording into per-track files, with optional normalization and silent-track removal. */
public final class ExportJob {
    static final int READ_BLOCK = 1 << 15;

    public enum Format {
        FLAC16("FLAC 16-bit", "flac", 16, false), FLAC24("FLAC 24-bit", "flac", 24, false),
        WAV16("WAV 16-bit", "wav", 16, false), WAV24("WAV 24-bit", "wav", 24, false),
        WAV32F("WAV 32-bit float", "wav", 32, true);
        public final String label, ext; public final int bits; public final boolean isFloat;
        Format(String l, String e, int b, boolean f) { label = l; ext = e; bits = b; isFloat = f; }
        public boolean flac() { return ext.equals("flac"); }
        @Override public String toString() { return label; }
    }

    public enum Normalize {
        NONE("Off"), PER_TRACK("Each file to target"), LINKED("All files, same gain (keeps balance)");
        public final String label;
        Normalize(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Range { WHOLE, SPLIT_AT_MARKERS, CUSTOM }

    /** One output file: a mono channel or a stereo pair. */
    public record Track(String name, int[] channels) {}

    public record Region(long start, long end, String label) {
        long length() { return end - start; }
    }

    public interface Progress {
        void update(String stage, double fraction, String detail);
        boolean cancelled();
    }

    public static final class Spec {
        public Session session;
        public List<Track> tracks = new ArrayList<>();
        public Format format = Format.FLAC16;
        public boolean dither = true;
        public Normalize normalize = Normalize.NONE;
        public double normalizeDb = -1.0;
        public boolean removeSilent = false;
        public double silentDb = -60.0;
        public Range range = Range.WHOLE;
        public long customStart, customEnd;
        public Path outDir;
        public boolean reaperProject = true;
        public boolean markerFiles = true;
    }

    public static final class Result {
        public final List<Path> files = new ArrayList<>();
        public final List<String> skippedSilent = new ArrayList<>();
        public final List<Path> folders = new ArrayList<>();
        public long bytesIn, bytesOut;
        public double seconds;
        public final StringBuilder report = new StringBuilder();
    }

    private final Spec spec;
    private final Progress progress;

    public ExportJob(Spec spec, Progress progress) {
        this.spec = spec;
        this.progress = progress == null ? new Progress() {
            public void update(String s, double f, String d) {}
            public boolean cancelled() { return false; }
        } : progress;
    }

    /** All channels as mono tracks, using the session's channel names. */
    public static List<Track> monoTracks(Session s) {
        List<Track> t = new ArrayList<>();
        for (int c = 0; c < s.channels; c++) t.add(new Track(s.channelNames[c], new int[] {c}));
        return t;
    }

    public List<Region> regions() {
        Session s = spec.session;
        List<Region> r = new ArrayList<>();
        switch (spec.range) {
            case CUSTOM -> {
                long a = Math.max(0, Math.min(spec.customStart, s.frames)), b = Math.max(a, Math.min(spec.customEnd, s.frames));
                r.add(new Region(a, b, "Range"));
            }
            case SPLIT_AT_MARKERS -> {
                List<Long> cuts = new ArrayList<>();
                cuts.add(0L);
                s.markers.stream().filter(m -> m > 0 && m < s.frames).sorted().distinct().forEach(cuts::add);
                cuts.add(s.frames);
                int k = 1;
                for (int i = 0; i + 1 < cuts.size(); i++) {
                    long a = cuts.get(i), b = cuts.get(i + 1);
                    if (b - a < s.sampleRate / 2) continue; // ignore slivers under half a second
                    r.add(new Region(a, b, String.format("Part %02d (%s)", k++, Util.hms(a / (double) s.sampleRate).replace(':', '.'))));
                }
            }
            default -> r.add(new Region(0, s.frames, "Full"));
        }
        return r;
    }

    public Result run() throws IOException {
        long t0 = System.nanoTime();
        Session s = spec.session;
        if (spec.tracks.isEmpty()) throw new IOException("No channels selected for export.");
        List<Region> regions = regions();
        // Only normalizing needs levels before encoding. Silent-track removal alone runs in one pass:
        // everything is encoded and the silent files are deleted afterwards (saves a full re-read of slow SD cards).
        boolean prepass = spec.normalize != Normalize.NONE;
        long work = 0;
        for (Region r : regions) work += r.length() * (prepass ? 2 : 1);
        final long totalWork = work;
        Files.createDirectories(spec.outDir);
        Result res = new Result();
        res.report.append("Export of ").append(s.label()).append('\n')
                .append("Source: ").append(s.dir).append('\n')
                .append("Format: ").append(spec.format.label).append(spec.dither && spec.format.bits < 32 ? " (TPDF dither)" : "").append('\n')
                .append("Normalize: ").append(spec.normalize.label)
                .append(spec.normalize != Normalize.NONE ? String.format(Locale.ROOT, " (%.1f dBFS peak)", spec.normalizeDb) : "").append('\n')
                .append("Remove silent tracks: ").append(spec.removeSilent ? String.format(Locale.ROOT, "yes, below %.0f dBFS peak", spec.silentDb) : "no").append("\n\n");

        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), spec.tracks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemon("encode"));
        ExecutorService reader = Executors.newSingleThreadExecutor(daemon("read"));
        long[] done = {0};
        try (AudioSource src = s.open()) {
            for (int ri = 0; ri < regions.size(); ri++) {
                Region region = regions.get(ri);
                Path dir = regions.size() > 1 ? spec.outDir.resolve(Util.safeFileName(region.label())) : spec.outDir;
                Files.createDirectories(dir);
                res.folders.add(dir);
                String where = regions.size() > 1 ? " — " + region.label() + " (" + (ri + 1) + "/" + regions.size() + ")" : "";

                long[] chPeaks = null;
                boolean whole = region.start() == 0 && region.end() == s.frames;
                if (prepass && whole && s.peaks != null) {
                    chPeaks = s.peaks.clone(); // levels already analyzed
                    done[0] += region.length();
                } else if (prepass) {
                    final long base = done[0];
                    chPeaks = analyze(src, region.start(), region.end(), reader, (f) ->
                            progress.update("Analyzing levels" + where, (base + f) / (double) Math.max(1, totalWork), ""));
                    if (whole) s.peaks = chPeaks.clone();
                    done[0] += region.length();
                    if (progress.cancelled()) throw new InterruptedIOException("Cancelled");
                }

                // decide tracks and gains
                List<Track> active = new ArrayList<>();
                List<Long> activePeaks = new ArrayList<>();
                for (Track t : spec.tracks) {
                    long pk = chPeaks == null ? -1 : trackPeak(chPeaks, t);
                    if (spec.removeSilent && chPeaks != null && Util.db(pk) < spec.silentDb) {
                        res.skippedSilent.add(t.name() + " (" + Util.dbLabel(pk) + ")" + where);
                        continue;
                    }
                    active.add(t); activePeaks.add(pk);
                }
                double target = Math.pow(10, spec.normalizeDb / 20.0) * 2147483648.0;
                long linkedPeak = 0;
                for (long p : activePeaks) linkedPeak = Math.max(linkedPeak, p);
                List<TrackWriter> writers = new ArrayList<>();
                Set<String> used = new HashSet<>();
                try {
                    for (int i = 0; i < active.size(); i++) {
                        Track t = active.get(i);
                        double gain = 1.0;
                        long pk = activePeaks.get(i);
                        if (spec.normalize == Normalize.PER_TRACK && pk > 0) gain = target / pk;
                        if (spec.normalize == Normalize.LINKED && linkedPeak > 0) gain = target / linkedPeak;
                        Path file = dir.resolve(uniqueName(fileStem(t), used) + "." + spec.format.ext);
                        writers.add(new TrackWriter(t, file, spec.format, s.sampleRate, spec.dither, gain, i * 7919L + ri));
                    }
                    if (writers.isEmpty()) {
                        res.report.append(region.label()).append(": every selected track was silent; nothing written.\n");
                        done[0] += region.length();
                        continue;
                    }
                    encodeRegion(src, region, writers, pool, reader, (f) ->
                            progress.update("Encoding " + spec.format.label + where, (done[0] + f) / (double) Math.max(1, totalWork),
                                    writers.size() + " files"));
                    done[0] += region.length();
                    for (TrackWriter w : writers) w.close();
                } catch (IOException | RuntimeException e) {
                    for (TrackWriter w : writers) { try { w.close(); } catch (IOException ignored) { } Files.deleteIfExists(w.path); }
                    throw e;
                }

                List<TrackWriter> kept = new ArrayList<>();
                for (TrackWriter w : writers) {
                    if (spec.removeSilent && chPeaks == null && Util.db(w.peakIn) < spec.silentDb) {
                        Files.deleteIfExists(w.path);
                        res.skippedSilent.add(w.track.name() + " (" + Util.dbLabel(w.peakIn) + ")" + where);
                    } else kept.add(w);
                }
                res.report.append(regions.size() > 1 ? region.label() + "\n" : "");
                for (TrackWriter w : kept) {
                    res.files.add(w.path);
                    res.bytesOut += Files.size(w.path);
                    res.report.append(String.format(Locale.ROOT, "  %-40s peak %9s  gain %+6.1f dB\n", w.path.getFileName(),
                            Util.dbLabel(w.peakIn), 20 * Math.log10(w.gain)));
                }
                List<Long> regionMarkers = new ArrayList<>();
                for (long m : s.markers) if (m > region.start() && m < region.end()) regionMarkers.add(m - region.start());
                if (spec.reaperProject)
                    Exports.writeReaper(dir.resolve(Util.safeFileName(s.label() + (regions.size() > 1 ? " - " + region.label() : "")) + ".rpp"),
                            kept, s.sampleRate, region.length(), regionMarkers);
                if (spec.markerFiles && !regionMarkers.isEmpty()) {
                    Exports.writeMarkersCsv(dir.resolve("markers.csv"), regionMarkers, s.sampleRate);
                    Exports.writeAudacityLabels(dir.resolve("markers_audacity.txt"), regionMarkers, s.sampleRate);
                }
            }
        } finally {
            pool.shutdownNow();
            reader.shutdownNow();
        }
        res.bytesIn = s.totalBytes();
        res.seconds = (System.nanoTime() - t0) / 1e9;
        if (!res.skippedSilent.isEmpty()) {
            res.report.append("\nSkipped as silent:\n");
            for (String n : res.skippedSilent) res.report.append("  ").append(n).append('\n');
        }
        res.report.append(String.format(Locale.ROOT, "\n%d files, %s written in %.1f s\n", res.files.size(), Util.bytes(res.bytesOut), res.seconds));
        Files.writeString(spec.outDir.resolve("export_report.txt"), res.report.toString());
        progress.update("Done", 1, "");
        return res;
    }

    private static long trackPeak(long[] chPeaks, Track t) {
        long p = 0;
        for (int c : t.channels()) p = Math.max(p, chPeaks[c]);
        return p;
    }

    private static String fileStem(Track t) {
        int[] c = t.channels();
        String num = c.length == 1 ? String.format("%02d", c[0] + 1) : String.format("%02d-%02d", c[0] + 1, c[c.length - 1] + 1);
        return Util.safeFileName(num + " " + t.name());
    }

    private static String uniqueName(String stem, Set<String> used) {
        String n = stem;
        for (int i = 2; !used.add(n.toLowerCase()); i++) n = stem + " (" + i + ")";
        return n;
    }

    interface Frac { void at(double framesDone); }

    /** Peak per channel over [start, end). */
    long[] analyze(AudioSource src, long start, long end, ExecutorService reader, Frac f) throws IOException {
        long[] peaks = new long[src.channels()];
        stream(src, start, end, reader, (block, n, pos) -> {
            for (int c = 0; c < block.length; c++) {
                int[] d = block[c];
                long p = peaks[c];
                for (int i = 0; i < n; i++) {
                    int v = d[i];
                    long a = v < 0 ? -(long) v : v;
                    if (a > p) p = a;
                }
                peaks[c] = p;
            }
            f.at(pos - start);
        });
        return peaks;
    }

    /** Analyzes the whole session (GUI "Analyze levels"). */
    public static long[] analyzeSession(Session s, Progress p) throws IOException {
        ExportJob j = new ExportJob(new Spec(), p);
        ExecutorService reader = Executors.newSingleThreadExecutor(daemon("read"));
        try (AudioSource src = s.open()) {
            return j.analyze(src, 0, s.frames, reader, f -> p.update("Analyzing levels", f / (double) Math.max(1, s.frames), ""));
        } finally {
            reader.shutdownNow();
        }
    }

    private void encodeRegion(AudioSource src, Region r, List<TrackWriter> writers, ExecutorService pool, ExecutorService reader, Frac f)
            throws IOException {
        stream(src, r.start(), r.end(), reader, (block, n, pos) -> {
            List<Callable<Void>> tasks = new ArrayList<>(writers.size());
            for (TrackWriter w : writers) tasks.add(() -> { w.write(block, n); return null; });
            try {
                for (Future<Void> fu : pool.invokeAll(tasks)) fu.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted");
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof IOException io) throw io;
                if (c instanceof RuntimeException re) throw re;
                throw new IOException(c);
            }
            f.at(pos - r.start());
        });
    }

    interface BlockSink { void accept(int[][] block, int n, long posAfter) throws IOException; }

    /** Reads [start,end) in blocks, reading the next block in the background while the current one is processed. */
    private void stream(AudioSource src, long start, long end, ExecutorService reader, BlockSink sink) throws IOException {
        int nc = src.channels();
        int[][] a = new int[nc][READ_BLOCK], b = new int[nc][READ_BLOCK];
        src.seek(start);
        long pos = start;
        Future<Integer> next = submitRead(reader, src, a, (int) Math.min(READ_BLOCK, end - pos));
        while (pos < end) {
            int n = join(next);
            if (n <= 0) break;
            pos += n;
            int[][] cur = a;
            a = b; b = cur;
            next = pos < end ? submitRead(reader, src, a, (int) Math.min(READ_BLOCK, end - pos)) : null;
            if (progress.cancelled()) { if (next != null) join(next); throw new InterruptedIOException("Cancelled"); }
            sink.accept(cur, n, pos);
        }
        if (next != null) join(next);
    }

    private static Future<Integer> submitRead(ExecutorService reader, AudioSource src, int[][] buf, int n) {
        return reader.submit(() -> n <= 0 ? 0 : src.read(buf, 0, n));
    }

    private static int join(Future<Integer> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause());
        }
    }

    static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }

    // ---------------------------------------------------------------------------------------- per-track writer

    /** Converts (gain, dither, word length) and encodes one output file. */
    static final class TrackWriter implements Closeable {
        final Track track;
        final Path path;
        final double gain;
        final int[] chans;
        final Converter[] conv;
        final int[][] work;
        final FlacEncoder flac;
        final WavWriter wav;
        long peakIn;

        TrackWriter(Track t, Path path, Format fmt, int rate, boolean dither, double gain, long seed) throws IOException {
            this.track = t; this.path = path; this.gain = gain; this.chans = t.channels();
            conv = new Converter[chans.length];
            work = new int[chans.length][READ_BLOCK];
            for (int i = 0; i < chans.length; i++) conv[i] = new Converter(fmt.bits, fmt.isFloat, dither, gain, seed * 31 + i);
            if (fmt.flac()) { flac = new FlacEncoder(path, chans.length, fmt.bits, rate, t.name()); wav = null; }
            else { wav = new WavWriter(path, chans.length, rate, fmt.bits, fmt.isFloat); flac = null; }
        }

        void write(int[][] block, int n) throws IOException {
            for (int i = 0; i < chans.length; i++) {
                int[] in = block[chans[i]];
                long p = peakIn;
                for (int k = 0; k < n; k++) { int v = in[k]; long a = v < 0 ? -(long) v : v; if (a > p) p = a; }
                peakIn = p;
                conv[i].convert(in, work[i], n);
            }
            if (flac != null) flac.write(work, n); else wav.write(work, n);
        }

        @Override public void close() throws IOException { if (flac != null) flac.close(); else wav.close(); }
    }

    /** 32-bit left-justified input -> target word length, with optional gain and TPDF dither. Digital silence stays silent. */
    static final class Converter {
        final int bits, shift;
        final boolean isFloat, dither;
        final double gain;
        final long max, min;
        long rng;

        Converter(int bits, boolean isFloat, boolean dither, double gain, long seed) {
            this.bits = bits; this.isFloat = isFloat; this.dither = dither && !isFloat && bits < 32; this.gain = gain;
            shift = 32 - bits;
            max = (1L << (bits - 1)) - 1; min = -(1L << (bits - 1));
            rng = seed * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL;
            if (rng == 0) rng = 1;
        }

        private long next() { long x = rng; x ^= x << 13; x ^= x >>> 7; x ^= x << 17; return rng = x; }

        void convert(int[] in, int[] out, int n) {
            if (isFloat) {
                double g = gain / 2147483648.0;
                for (int i = 0; i < n; i++) out[i] = Float.floatToRawIntBits((float) (in[i] * g));
                return;
            }
            if (gain == 1.0) {
                if (shift == 0) { System.arraycopy(in, 0, out, 0, n); return; }
                long mask = (1L << shift) - 1, half = 1L << (shift - 1);
                for (int i = 0; i < n; i++) {
                    int v = in[i];
                    if ((v & mask) == 0) { out[i] = v >> shift; continue; } // exact, no dither needed
                    long t = v + half;
                    if (dither) { long r = next(); t += ((r & mask) - ((r >>> 32) & mask)); }
                    long y = t >> shift;
                    out[i] = (int) (y > max ? max : y < min ? min : y);
                }
                return;
            }
            double scale = gain / (double) (1L << shift);
            for (int i = 0; i < n; i++) {
                int v = in[i];
                if (v == 0) { out[i] = 0; continue; }
                double t = v * scale + 0.5;
                if (dither) { long r = next(); t += ((r & 0xFFFFFFFFL) - (r >>> 32)) * (1.0 / 4294967296.0); }
                long y = (long) Math.floor(t);
                out[i] = (int) (y > max ? max : y < min ? min : y);
            }
        }
    }
}
