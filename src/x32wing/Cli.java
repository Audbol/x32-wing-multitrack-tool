package x32wing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command line front end. */
final class Cli {
    private Cli() {}

    static final String USAGE = """
            X32 / M32 / WING multitrack tool

            Usage:
              x32wing                          start the graphical tool
              x32wing scan <card|folder>       list recordings found
              x32wing flac <card|folder|session> [options]
                                               quick convert: every channel to its own 16-bit FLAC
              x32wing export <card|folder|session> [options]
              x32wing build <out folder> <file.wav|file.flac>... [--name NAME] [--markers 1:30,5:00]
                                               build an X-Live/WING-LIVE card session for virtual soundcheck
              x32wing repair <card|folder>     fix headers of unfinalized recordings (card pulled / power lost)
              x32wing selftest                 run the built-in tests

            Export options:
              -o, --out <folder>        output folder (default: <Music>/X32-Wing Exports)
              --format <f>              flac16 (default) | flac24 | wav16 | wav24 | wav32f
              --no-dither               truncate/round without TPDF dither
              --normalize [track|linked]  normalize peaks (each file, or one gain for all)
              --target <dBFS>           normalize target peak (default -1.0)
              --remove-silent           drop channels whose peak stays below the threshold
              --silent-db <dBFS>        silence threshold (default -60)
              --split-markers           one folder per song, cut at the session markers
              --from <time> --to <time> export only this range (h:mm:ss.sss)
              --channels 1-16,19,21     only these channels
              --stereo 17-18,31-32      export these pairs as stereo files
              --names <file>            channel names from .scn, .snap or a text list
              --session <n>             when several recordings are found, pick #n (from scan)
              --no-reaper               do not write a REAPER project
            """;

    static int run(String[] args) throws Exception {
        String cmd = args[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "help", "-h", "--help", "/?" -> { System.out.println(USAGE); return 0; }
            case "selftest" -> { return SelfTest.run(args.length > 1 && args[1].equals("--keep")) ? 0 : 1; }
            case "scan" -> { return scan(args); }
            case "flac", "export" -> { return export(args, cmd.equals("flac")); }
            case "build" -> { return build(args); }
            case "repair" -> { return repair(args); }
            default -> { System.err.println("Unknown command: " + args[0] + "\n\n" + USAGE); return 2; }
        }
    }

    private static List<Session> load(String path) throws IOException {
        List<String> warn = new ArrayList<>();
        List<Session> s = Session.scan(Path.of(path), warn);
        for (String w : warn) System.err.println("warning: " + w);
        return s;
    }

    private static int scan(String[] a) throws IOException {
        if (a.length < 2) { System.err.println("scan needs a path"); return 2; }
        List<Session> list = load(a[1]);
        if (list.isEmpty()) { System.out.println("No recordings found."); return 1; }
        for (int i = 0; i < list.size(); i++) {
            Session s = list.get(i);
            System.out.printf("#%d  %s%n    %s | %d take(s) | %d marker(s)%s%n    %s%n", i + 1, s.label(), ascii(s.summary()),
                    s.takes.size(), s.markers.size(), s.anyBrokenHeaders() ? " | UNFINALIZED (run repair)" : "", s.dir);
        }
        return 0;
    }

    private static int export(String[] a, boolean quick) throws Exception {
        if (a.length < 2) { System.err.println(a[0] + " needs a path"); return 2; }
        ExportJob.Spec spec = new ExportJob.Spec();
        Path out = null;
        String names = null, channels = null, stereo = null;
        int pick = -1;
        double from = 0, to = -1;
        for (int i = 2; i < a.length; i++) {
            String o = a[i];
            switch (o) {
                case "-o", "--out" -> out = Path.of(a[++i]);
                case "--format" -> spec.format = parseFormat(a[++i]);
                case "--no-dither" -> spec.dither = false;
                case "--normalize" -> {
                    spec.normalize = ExportJob.Normalize.PER_TRACK;
                    if (i + 1 < a.length && a[i + 1].equalsIgnoreCase("linked")) { spec.normalize = ExportJob.Normalize.LINKED; i++; }
                    else if (i + 1 < a.length && a[i + 1].equalsIgnoreCase("track")) i++;
                }
                case "--target" -> spec.normalizeDb = Double.parseDouble(a[++i]);
                case "--remove-silent" -> spec.removeSilent = true;
                case "--silent-db" -> spec.silentDb = Double.parseDouble(a[++i]);
                case "--split-markers" -> spec.range = ExportJob.Range.SPLIT_AT_MARKERS;
                case "--from" -> { spec.range = ExportJob.Range.CUSTOM; from = Util.parseTime(a[++i]); }
                case "--to" -> { spec.range = ExportJob.Range.CUSTOM; to = Util.parseTime(a[++i]); }
                case "--channels" -> channels = a[++i];
                case "--stereo" -> stereo = a[++i];
                case "--names" -> names = a[++i];
                case "--session" -> pick = Integer.parseInt(a[++i]);
                case "--no-reaper" -> spec.reaperProject = false;
                default -> { System.err.println("Unknown option " + o); return 2; }
            }
        }
        if (quick && spec.format != ExportJob.Format.FLAC16 && !List.of(a).contains("--format")) spec.format = ExportJob.Format.FLAC16;
        List<Session> list = load(a[1]);
        if (list.isEmpty()) { System.err.println("No recordings found in " + a[1]); return 1; }
        List<Session> todo = pick > 0 ? List.of(list.get(pick - 1)) : list;
        Path root = out != null ? out : defaultOut();
        for (Session s : todo) {
            if (names != null) applyNames(s, NameImport.read(Path.of(names)));
            spec.session = s;
            spec.customStart = Math.round(from * s.sampleRate);
            spec.customEnd = to < 0 ? s.frames : Math.round(to * s.sampleRate);
            spec.tracks = tracks(s, channels, stereo);
            spec.outDir = root.resolve(Util.safeFileName(s.label()));
            System.out.println(s.label() + "  (" + ascii(s.summary()) + ")");
            ExportJob.Result r = new ExportJob(spec, consoleProgress()).run();
            System.out.println();
            double audio = 0;
            for (ExportJob.Region rg : new ExportJob(spec, null).regions()) audio += rg.length() / (double) s.sampleRate;
            System.out.printf(Locale.ROOT, "  %d files, %s in %.1f s (%.0fx realtime) -> %s%n", r.files.size(), Util.bytes(r.bytesOut), r.seconds,
                    audio / Math.max(0.001, r.seconds), spec.outDir);
            for (String sk : r.skippedSilent) System.out.println("  skipped silent: " + sk);
        }
        return 0;
    }

    private static String ascii(String s) { return s.replace(" · ", " | "); }

    static Path defaultOut() {
        Path music = Path.of(System.getProperty("user.home"), "Music");
        return (Files.isDirectory(music) ? music : Path.of(System.getProperty("user.home"))).resolve("X32-Wing Exports");
    }

    static void applyNames(Session s, List<String> names) {
        for (int i = 0; i < Math.min(names.size(), s.channels); i++)
            if (names.get(i) != null && !names.get(i).isBlank()) s.channelNames[i] = names.get(i);
    }

    static ExportJob.Format parseFormat(String f) {
        return switch (f.toLowerCase(Locale.ROOT)) {
            case "flac16", "flac" -> ExportJob.Format.FLAC16;
            case "flac24" -> ExportJob.Format.FLAC24;
            case "wav16" -> ExportJob.Format.WAV16;
            case "wav24", "wav" -> ExportJob.Format.WAV24;
            case "wav32f", "wav32", "float" -> ExportJob.Format.WAV32F;
            default -> throw new IllegalArgumentException("Unknown format " + f);
        };
    }

    private static List<ExportJob.Track> tracks(Session s, String channels, String stereo) {
        boolean[] sel = new boolean[s.channels];
        if (channels == null) java.util.Arrays.fill(sel, true);
        else for (int c : ranges(channels)) if (c >= 1 && c <= s.channels) sel[c - 1] = true;
        int[] pairWith = new int[s.channels];
        java.util.Arrays.fill(pairWith, -1);
        if (stereo != null)
            for (String part : stereo.split(",")) {
                String[] ab = part.trim().split("-");
                int x = Integer.parseInt(ab[0].trim()) - 1, y = ab.length > 1 ? Integer.parseInt(ab[1].trim()) - 1 : x + 1;
                if (x >= 0 && y < s.channels && x != y) pairWith[x] = y;
            }
        List<ExportJob.Track> t = new ArrayList<>();
        boolean[] usedUp = new boolean[s.channels];
        for (int c = 0; c < s.channels; c++) {
            if (!sel[c] || usedUp[c]) continue;
            int p = pairWith[c];
            if (p >= 0) {
                usedUp[p] = true;
                String n = s.channelNames[c].equals(s.channelNames[p]) ? s.channelNames[c] : s.channelNames[c] + " + " + s.channelNames[p];
                t.add(new ExportJob.Track(n, new int[] {c, p}));
            } else t.add(new ExportJob.Track(s.channelNames[c], new int[] {c}));
        }
        return t;
    }

    static List<Integer> ranges(String spec) {
        List<Integer> r = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                String[] ab = p.split("-");
                for (int i = Integer.parseInt(ab[0].trim()); i <= Integer.parseInt(ab[1].trim()); i++) r.add(i);
            } else r.add(Integer.parseInt(p));
        }
        return r;
    }

    private static int build(String[] a) throws IOException {
        if (a.length < 3) { System.err.println("build needs an output folder and input files"); return 2; }
        Path out = Path.of(a[1]);
        List<Path> in = new ArrayList<>();
        String name = "";
        List<Double> markers = new ArrayList<>();
        for (int i = 2; i < a.length; i++) {
            if (a[i].equals("--name")) name = a[++i];
            else if (a[i].equals("--markers")) for (String m : a[++i].split(",")) markers.add(Util.parseTime(m));
            else in.add(Path.of(a[i]));
        }
        Path dir = new XLiveBuilder().build(in, out, name, markers, consoleProgress());
        System.out.println("\nCard session written to " + dir);
        System.out.println("Copy the X_LIVE folder to the root of the SD card.");
        return 0;
    }

    private static int repair(String[] a) throws IOException {
        if (a.length < 2) { System.err.println("repair needs a path"); return 2; }
        int fixed = 0;
        for (Session s : load(a[1]))
            for (WavFile w : s.takes)
                if (w.headerBroken) {
                    w.repairHeader();
                    fixed++;
                    System.out.println("repaired " + w.path);
                }
        System.out.println(fixed == 0 ? "No broken headers found." : fixed + " file(s) repaired.");
        return 0;
    }

    private static ExportJob.Progress consoleProgress() {
        return new ExportJob.Progress() {
            long last;
            public void update(String stage, double f, String d) {
                long now = System.currentTimeMillis();
                if (now - last < 250 && f < 1) return;
                last = now;
                System.out.printf(Locale.ROOT, "\r  %-60s %5.1f%%", stage, f * 100);
            }
            public boolean cancelled() { return false; }
        };
    }
}
