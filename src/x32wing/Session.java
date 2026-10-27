package x32wing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One multitrack recording: an X-Live / WING-LIVE card session, a plain multichannel WAV, or several of those combined side by side. */
public final class Session {
    /** Card takes are numbered in hex: 00000009.WAV, 0000000A.WAV ... 00000010.WAV. */
    private static final Pattern TAKE = Pattern.compile("[0-9A-F]{8}\\.wav", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_DIR = Pattern.compile("[0-9A-Fa-f]{8}");

    public final String id;
    public final Path dir;
    /** Card sessions: the takes; combined sessions: empty (see parts). */
    public final List<WavFile> takes;
    public final List<Session> parts;
    public final SeLog log;
    public final Path logPath;
    public final LocalDateTime date;
    public final int channels, sampleRate;
    public final long frames;
    public final String formatLabel;
    public String name;
    public final List<Long> markers = new ArrayList<>();
    public final String[] channelNames;
    /** Peak per channel (32-bit full scale = 2^31), filled by analysis; null until analysed. */
    public long[] peaks;

    private Session(String id, Path dir, List<WavFile> takes, List<Session> parts, SeLog log, Path logPath, LocalDateTime date,
                    int channels, int rate, long frames, String formatLabel, String name) {
        this.id = id; this.dir = dir; this.takes = takes; this.parts = parts; this.log = log; this.logPath = logPath;
        this.date = date; this.channels = channels; this.sampleRate = rate; this.frames = frames;
        this.formatLabel = formatLabel; this.name = name;
        channelNames = new String[channels];
        for (int i = 0; i < channels; i++) channelNames[i] = String.format("Ch %02d", i + 1);
    }

    public boolean isCardSession() { return log != null; }
    public double seconds() { return frames / (double) sampleRate; }
    public long totalBytes() {
        long t = 0;
        for (WavFile w : takes) t += w.fileSize;
        for (Session s : parts) t += s.totalBytes();
        return t;
    }
    public boolean anyBrokenHeaders() {
        for (WavFile w : takes) if (w.headerBroken) return true;
        for (Session s : parts) if (s.anyBrokenHeaders()) return true;
        return false;
    }

    public AudioSource open() {
        if (!parts.isEmpty()) {
            List<AudioSource> srcs = new ArrayList<>();
            for (Session s : parts) srcs.add(s.open());
            return new AudioSource.Concat(srcs);
        }
        return new AudioSource.WavChain(takes);
    }

    /** Label used for list display and export folder names. */
    public String label() {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isBlank()) sb.append(name.trim());
        if (date != null) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")));
        }
        if (sb.isEmpty()) sb.append(id);
        return sb.toString();
    }

    public String summary() {
        return String.format(Locale.ROOT, "%d ch · %s · %s · %s · %s", channels, khz(sampleRate), formatLabel,
                Util.hms(seconds()), Util.bytes(totalBytes()));
    }

    static String khz(int r) { return (r % 1000 == 0 ? String.valueOf(r / 1000) : String.format(Locale.ROOT, "%.1f", r / 1000.0)) + " kHz"; }

    /** Saves name and markers back to SE_LOG.BIN (card sessions only). */
    public void saveLog() throws IOException {
        if (log == null || logPath == null) throw new IOException("This recording has no SE_LOG.BIN to save to.");
        log.name = name == null ? "" : name;
        log.markers.clear();
        log.markers.addAll(markers.subList(0, Math.min(markers.size(), SeLog.MAX_MARKERS)));
        log.write(logPath);
    }

    /** Combines sessions recorded in parallel (e.g. WING card A ch 1-32 + card B ch 33-64) into one. */
    public static Session combine(List<Session> list) throws IOException {
        if (list.size() < 2) throw new IOException("Select at least two sessions to combine.");
        int rate = list.get(0).sampleRate, ch = 0; long f = 0;
        for (Session s : list) {
            if (s.sampleRate != rate) throw new IOException("Sessions have different sample rates.");
            ch += s.channels; f = Math.max(f, s.frames);
        }
        Session first = list.get(0);
        Session c = new Session(first.id + "+", first.dir, List.of(), List.copyOf(list), null, null, first.date, ch, rate, f,
                first.formatLabel, (first.name == null ? "" : first.name + " ") + "(combined)");
        int k = 0;
        for (Session s : list) for (int i = 0; i < s.channels; i++, k++) c.channelNames[k] = String.format("Ch %02d", k + 1);
        c.markers.addAll(first.markers);
        return c;
    }

    /** Finds every recording under root (an SD card, an X_LIVE folder, a session folder or a single WAV). */
    public static List<Session> scan(Path root, List<String> warnings) throws IOException {
        List<Session> out = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            Session s = fromSingle(root, warnings);
            if (s != null) out.add(s);
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> st = Files.walk(root, 6)) { dirs = st.filter(Files::isDirectory).sorted().toList(); }
        for (Path d : dirs) {
            List<Path> wavs;
            try (Stream<Path> st = Files.list(d)) {
                wavs = st.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".wav"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).toList();
            } catch (IOException e) { warnings.add(d + ": " + e.getMessage()); continue; }
            if (wavs.isEmpty()) continue;
            Path log = findLog(d);
            List<Path> takes = wavs.stream().filter(p -> TAKE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().substring(0, 8), 16))).toList();
            if (log != null || !takes.isEmpty()) {
                Session s = fromCard(d, takes.isEmpty() ? wavs : takes, log, warnings);
                if (s != null) out.add(s);
                for (Path w : wavs) if (!takes.contains(w) && !takes.isEmpty()) addSingle(out, w, warnings);
            } else {
                for (Path w : wavs) addSingle(out, w, warnings);
            }
        }
        out.sort(Comparator.comparing((Session s) -> s.date == null ? LocalDateTime.MIN : s.date).thenComparing(s -> s.id));
        return out;
    }

    private static void addSingle(List<Session> out, Path w, List<String> warnings) {
        Session s = fromSingle(w, warnings);
        if (s != null) out.add(s);
    }

    private static Path findLog(Path d) throws IOException {
        try (Stream<Path> st = Files.list(d)) {
            return st.filter(p -> p.getFileName().toString().equalsIgnoreCase("SE_LOG.BIN")).findFirst().orElse(null);
        }
    }

    private static Session fromCard(Path d, List<Path> files, Path logPath, List<String> warnings) {
        List<WavFile> takes = new ArrayList<>();
        for (Path p : files) {
            try { takes.add(WavFile.open(p)); } catch (IOException e) { warnings.add(e.getMessage()); }
        }
        if (takes.isEmpty()) return null;
        WavFile f0 = takes.get(0);
        for (WavFile w : takes) {
            if (w.channels != f0.channels || w.sampleRate != f0.sampleRate || w.bitsPerSample != f0.bitsPerSample || w.isFloat != f0.isFloat) {
                warnings.add(d + ": takes have mismatched formats; using only " + f0.path.getFileName());
                takes = List.of(f0);
                break;
            }
        }
        SeLog log = null;
        if (logPath != null) {
            try { log = SeLog.read(logPath); } catch (IOException e) { warnings.add(d + ": " + e.getMessage()); }
        }
        String dirName = d.getFileName() == null ? d.toString() : d.getFileName().toString();
        LocalDateTime date = null;
        if (log != null) date = SeLog.unpackDate(log.sessionId);
        if (date == null && HEX_DIR.matcher(dirName).matches()) date = SeLog.unpackDate((int) Long.parseLong(dirName, 16));
        long frames = 0;
        for (WavFile w : takes) frames += w.frames();
        if (log != null && log.numTakes > 0 && log.numTakes != takes.size())
            warnings.add(d + ": SE_LOG.BIN lists " + log.numTakes + " take(s) but " + takes.size() + " were found; the recording may be incomplete.");
        else if (log != null && log.totalFrames > 0 && log.totalFrames != frames && takes.stream().noneMatch(w -> w.headerBroken))
            warnings.add(d + ": SE_LOG.BIN length (" + log.totalFrames + " samples) differs from the take files (" + frames + ").");
        Session s = new Session(dirName, d, List.copyOf(takes), List.of(), log, log == null ? null : logPath, date,
                f0.channels, f0.sampleRate, frames, f0.formatLabel(), log == null ? "" : log.name);
        if (log != null) for (long m : log.markers) if (m < frames) s.markers.add(m);
        return s;
    }

    private static Session fromSingle(Path p, List<String> warnings) {
        try {
            WavFile w = WavFile.open(p);
            String fn = p.getFileName().toString();
            return new Session(fn, p.getParent(), List.of(w), List.of(), null, null, null, w.channels, w.sampleRate,
                    w.frames(), w.formatLabel(), fn.replaceFirst("(?i)\\.wav$", ""));
        } catch (IOException e) {
            warnings.add(e.getMessage());
            return null;
        }
    }

    @Override public String toString() { return label(); }
}
