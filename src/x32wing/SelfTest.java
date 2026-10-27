package x32wing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/** End-to-end tests on a synthetic card session. Run with: x32wing selftest */
final class SelfTest {
    private SelfTest() {}

    private static int failures;

    static boolean run() throws IOException { return run(false); }

    /** keep = leave the generated test files on disk (path is printed). */
    static boolean run(boolean keep) throws IOException {
        failures = 0;
        Path tmp = Files.createTempDirectory("x32wing-selftest");
        try {
            body(tmp);
        } finally {
            if (keep) System.out.println("\nTest files kept in " + tmp);
            else try (Stream<Path> st = Files.walk(tmp)) { st.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        return failures == 0;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    private static void body(Path tmp) throws IOException {
        final int rate = 48000, inCh = 30, frames = rate * 6 + 1234;
        Random rnd = new Random(42);
        int[][] src = new int[inCh][frames]; // 24-bit values
        for (int c = 0; c < inCh; c++) {
            double f = 55.0 * (c + 1), amp = (0.05 + 0.9 * rnd.nextDouble()) * (1 << 23);
            for (int i = 0; i < frames; i++) {
                double v = switch (c) {
                    case 5 -> 0;                                                   // unused input: digital silence
                    case 6 -> rnd.nextGaussian() * 30;                             // unused input: noise floor ~ -100 dBFS
                    case 7 -> (i / 200 % 2 == 0 ? 1 : -1) * ((1 << 23) - 1);       // full scale square
                    case 11 -> 0.8 * src[10][i] + rnd.nextGaussian() * 2000;       // right of a stereo pair
                    default -> amp * Math.sin(2 * Math.PI * f * i / rate) * (0.6 + 0.4 * Math.sin(i / 9000.0)) + rnd.nextGaussian() * 300;
                };
                src[c][i] = (int) Math.max(-(1 << 23), Math.min((1 << 23) - 1, Math.round(v)));
            }
        }
        Path inDir = Files.createDirectories(tmp.resolve("in"));
        List<Path> inputs = new ArrayList<>();
        for (int c = 0; c < inCh; c++) {
            Path p = inDir.resolve(String.format("%02d.wav", c + 1));
            try (WavWriter w = new WavWriter(p, 1, rate, 24, false)) { w.write(new int[][] {src[c]}, frames); }
            inputs.add(p);
        }

        System.out.println("Card session builder / scanner");
        XLiveBuilder b = new XLiveBuilder();
        b.maxTakeBytes = 2L << 20; // force many takes
        Path card = tmp.resolve("card");
        Path sdir = b.build(inputs, card, "Test Gig", List.of(2.0, 4.0), null);
        List<String> warn = new ArrayList<>();
        List<Session> found = Session.scan(card, warn);
        check(found.size() == 1 && warn.isEmpty(), "one session found, no warnings " + warn);
        Session s = found.get(0);
        check(s.channels == 32 && s.sampleRate == rate && s.frames == frames, "32 ch / 48 kHz / length " + s.frames);
        check(s.takes.size() == (int) Math.ceil(frames / (double) ((2L << 20) / 128)), s.takes.size() + " takes spanned seamlessly");
        check(s.takes.get(9).path.getFileName().toString().equals("0000000A.WAV")
                && s.takes.get(16).path.getFileName().toString().equals("00000011.WAV"), "takes named and ordered in hex like the card (..09, 0A..0F, 10..)");
        check(s.isCardSession() && "Test Gig".equals(s.name), "SE_LOG name '" + s.name + "'");
        check(s.markers.equals(List.of(2L * rate, 4L * rate)), "markers " + s.markers);
        check(s.takes.get(0).dataOffset == 32768 && s.takes.get(0).bitsPerSample == 32, "card WAV layout: 32-bit, audio at 0x8000");
        SeLog log = SeLog.read(s.logPath);
        check(log.totalFrames == frames && log.takeSizes.length == s.takes.size() && log.takeSizes[0] == (2L << 20) / 4,
                "SE_LOG total length and take sizes");

        System.out.println("FLAC 24-bit (lossless)");
        Path o = tmp.resolve("out24");
        ExportJob.Result r = export(s, ExportJob.Format.FLAC24, false, o, null);
        check(r.files.size() == 32, r.files.size() + " files");
        boolean exact = true, md5 = true;
        for (int c = 0; c < 32; c++) {
            Decoded d = decode(r.files.get(c));
            md5 &= d.md5;
            int[] want = c < inCh ? src[c] : new int[frames];
            exact &= d.n == frames && java.util.Arrays.equals(d.data[0], 0, frames, want, 0, frames);
        }
        check(exact, "all 32 channels decode bit-identical to the source");
        check(md5, "MD5 signatures verify");
        long wavBytes = 32L * frames * 3;
        System.out.printf(Locale.ROOT, "        size %s vs %s as 24-bit WAV (%.0f%%)%n", Util.bytes(r.bytesOut), Util.bytes(wavBytes), 100.0 * r.bytesOut / wavBytes);

        System.out.println("FLAC 16-bit");
        r = export(s, ExportJob.Format.FLAC16, false, tmp.resolve("out16"), null);
        boolean rounded = true;
        for (int c = 0; c < inCh; c++) {
            int[] d = decode(r.files.get(c)).data[0];
            for (int i = 0; i < frames; i++) {
                long want = Math.min(32767, (src[c][i] + 128L) >> 8);
                if (d[i] != want) { rounded = false; break; }
            }
        }
        check(rounded, "undithered 16-bit equals rounded source");
        r = export(s, ExportJob.Format.FLAC16, true, tmp.resolve("out16d"), null);
        boolean close = true, silentStays = true;
        for (int c = 0; c < inCh; c++) {
            int[] d = decode(r.files.get(c)).data[0];
            for (int i = 0; i < frames; i++) if (Math.abs(d[i] * 256L - src[c][i]) > 2 * 256) close = false;
            if (c == 5) for (int v : d) if (v != 0) silentStays = false;
        }
        check(close, "dithered 16-bit within 2 LSB of source");
        check(silentStays, "digital silence stays digital silence (no dither noise added)");

        System.out.println("Stereo pairs");
        ExportJob.Spec sp = spec(s, ExportJob.Format.FLAC16, false, tmp.resolve("stereo"));
        sp.tracks = List.of(new ExportJob.Track("OH", new int[] {10, 11}));
        r = new ExportJob(sp, null).run();
        Decoded st = decode(r.files.get(0));
        boolean stereoOk = st.data.length == 2;
        for (int k = 0; k < 2 && stereoOk; k++)
            for (int i = 0; i < frames; i++) if (st.data[k][i] != Math.min(32767, (src[10 + k][i] + 128L) >> 8)) { stereoOk = false; break; }
        check(stereoOk && r.files.get(0).getFileName().toString().startsWith("11-12 OH"), "stereo file 11-12 decodes exactly");

        System.out.println("Remove silent channels");
        sp = spec(s, ExportJob.Format.FLAC16, true, tmp.resolve("silent"));
        sp.removeSilent = true; sp.silentDb = -60;
        r = new ExportJob(sp, null).run();
        List<String> names = r.files.stream().map(p -> p.getFileName().toString()).toList();
        check(r.files.size() == 28 && names.stream().noneMatch(n -> n.startsWith("06 ") || n.startsWith("07 ") || n.startsWith("31 ") || n.startsWith("32 ")),
                "28 files; channels 6, 7, 31, 32 skipped as silent " + r.skippedSilent);

        System.out.println("Normalize");
        sp = spec(s, ExportJob.Format.FLAC24, false, tmp.resolve("norm"));
        sp.removeSilent = true; sp.normalize = ExportJob.Normalize.PER_TRACK; sp.normalizeDb = -1.0;
        r = new ExportJob(sp, null).run();
        boolean normOk = true;
        for (Path p : r.files) {
            double db = Util.db(peak(decode(p).data[0]) << 8);
            if (Math.abs(db + 1.0) > 0.05) { normOk = false; System.out.println("        " + p.getFileName() + " peaks at " + db); }
        }
        check(normOk && r.files.size() == 28, "each file peaks at -1.0 dBFS");
        sp.normalize = ExportJob.Normalize.LINKED; sp.outDir = tmp.resolve("normLinked");
        r = new ExportJob(sp, null).run();
        long loud = 0; for (int c = 0; c < inCh; c++) loud = Math.max(loud, peak(src[c]));
        double g = Math.pow(10, -1 / 20.0) * (1 << 23) / loud;
        int[] d0 = decode(r.files.get(0)).data[0];
        boolean linkedOk = true;
        for (int i = 0; i < frames; i += 97) if (Math.abs(d0[i] - src[0][i] * g) > 1.01) linkedOk = false;
        check(linkedOk, String.format(Locale.ROOT, "linked: every file gets the same %+.2f dB gain", 20 * Math.log10(g)));

        System.out.println("Split at markers");
        sp = spec(s, ExportJob.Format.FLAC16, true, tmp.resolve("split"));
        sp.range = ExportJob.Range.SPLIT_AT_MARKERS;
        sp.tracks = List.of(new ExportJob.Track("Kick", new int[] {0}));
        r = new ExportJob(sp, null).run();
        long sum = 0;
        for (Path p : r.files) sum += decode(p).n;
        check(r.folders.size() == 3 && r.files.size() == 3 && sum == frames, "3 songs, lengths add up to the whole recording");

        System.out.println("WAV output");
        r = export(s, ExportJob.Format.WAV24, false, tmp.resolve("wav24"), new int[] {0, 7});
        check(sameAs(r.files.get(0), src[0], 8) && sameAs(r.files.get(1), src[7], 8), "24-bit WAV bit-identical");
        r = export(s, ExportJob.Format.WAV32F, false, tmp.resolve("wavf"), new int[] {0});
        WavFile wf = WavFile.open(r.files.get(0));
        check(wf.isFloat && wf.bitsPerSample == 32 && sameAs(r.files.get(0), src[0], 8), "32-bit float WAV exact");

        System.out.println("Unfinalized recording");
        Path broken = Files.createDirectories(tmp.resolve("broken/X_LIVE/4B5C62B0"));
        Path take = broken.resolve("00000001.WAV");
        Files.copy(s.takes.get(0).path, take);
        try (FileChannel ch = FileChannel.open(take, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0), 32764);
            ch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0), 4);
        }
        WavFile bw = WavFile.open(take);
        check(bw.headerBroken && bw.frames() == s.takes.get(0).frames(), "zeroed header detected, length recovered from file size");
        bw.repairHeader();
        WavFile fixed = WavFile.open(take);
        check(!fixed.headerBroken && fixed.frames() == bw.frames(), "header repaired in place");
        Session bs = Session.scan(tmp.resolve("broken"), new ArrayList<>()).get(0);
        check(bs.date != null && bs.date.getYear() == 2017, "session date decoded from folder name " + bs.date);

        System.out.println("Rebuild card session from FLAC archive");
        List<Path> flacs = new ArrayList<>();
        try (Stream<Path> st2 = Files.list(tmp.resolve("out24"))) { st2.filter(p -> p.toString().endsWith(".flac")).sorted().forEach(flacs::add); }
        Path card2 = tmp.resolve("card2");
        new XLiveBuilder().build(flacs, card2, "Rebuilt", List.of(1.5), null);
        Session s2 = Session.scan(card2, new ArrayList<>()).get(0);
        check(s2.channels == 32 && s2.frames == frames && s2.markers.size() == 1, "rebuilt session 32 ch, same length");
        check(sameAudio(s, s2), "rebuilt card audio is bit-identical to the original");

        System.out.println("SE_LOG edit");
        s.name = "Renamed";
        s.markers.add(5L * rate);
        s.saveLog();
        Session s3 = Session.scan(card, new ArrayList<>()).get(0);
        check("Renamed".equals(s3.name) && s3.markers.size() == 3 && Files.exists(s.logPath.resolveSibling("SE_LOG.BIN.bak")),
                "name and markers saved, backup kept");

        System.out.println("Channel names");
        Path scn = tmp.resolve("show.scn");
        Files.writeString(scn, "#2.1# \"Show\" \"\" %000000000 1 X32\n/ch/01/config \"Kick In\" 1 RD 1\n/ch/02/config \"Snare\" 2 YE 2\n/ch/04/config \"Bass\" 3 GN 4\n");
        List<String> xn = NameImport.read(scn);
        check(xn.size() == 4 && "Kick In".equals(xn.get(0)) && xn.get(2) == null && "Bass".equals(xn.get(3)), "X32 scene names " + xn);
        Path snap = tmp.resolve("show.snap");
        Files.writeString(snap, "{\"type\":\"snapshot\",\"ae_data\":{\"ch\":{\"1\":{\"name\":\"Vox\",\"col\":1},\"2\":{\"name\":\"Gtr \\\"L\\\"\"}}}}");
        List<String> wn = NameImport.read(snap);
        check(wn.size() == 2 && "Vox".equals(wn.get(0)) && "Gtr \"L\"".equals(wn.get(1)), "WING snapshot names " + wn);

        System.out.println("Speed");
        long t0 = System.nanoTime();
        r = export(s, ExportJob.Format.FLAC16, true, tmp.resolve("speed"), null);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf(Locale.ROOT, "        32 ch to 16-bit FLAC: %.0fx realtime on %d cores%n", s.seconds() / sec, Runtime.getRuntime().availableProcessors());
    }

    private static ExportJob.Spec spec(Session s, ExportJob.Format f, boolean dither, Path out) {
        ExportJob.Spec sp = new ExportJob.Spec();
        sp.session = s; sp.format = f; sp.dither = dither; sp.outDir = out;
        sp.tracks = ExportJob.monoTracks(s);
        return sp;
    }

    private static ExportJob.Result export(Session s, ExportJob.Format f, boolean dither, Path out, int[] only) throws IOException {
        ExportJob.Spec sp = spec(s, f, dither, out);
        if (only != null) {
            sp.tracks = new ArrayList<>();
            for (int c : only) sp.tracks.add(new ExportJob.Track(s.channelNames[c], new int[] {c}));
        }
        return new ExportJob(sp, null).run();
    }

    record Decoded(int[][] data, int n, boolean md5) {}

    static Decoded decode(Path p) throws IOException {
        try (FlacDecoder d = new FlacDecoder(p)) {
            d.enableVerify();
            int[][] out = new int[d.channels][(int) d.totalSamples + d.maxBlockSize];
            int[][] fr = new int[d.channels][d.maxBlockSize];
            int n = 0, k;
            while ((k = d.readFrame(fr)) > 0) {
                for (int c = 0; c < d.channels; c++) System.arraycopy(fr[c], 0, out[c], n, k);
                n += k;
            }
            return new Decoded(out, n, d.md5Matches() && n == d.totalSamples);
        }
    }

    private static long peak(int[] x) {
        long p = 0;
        for (int v : x) p = Math.max(p, Math.abs((long) v));
        return p;
    }

    private static boolean sameAs(Path wav, int[] want24, int shift) throws IOException {
        WavFile w = WavFile.open(wav);
        int[][] buf = new int[1][(int) w.frames()];
        try (WavFile.Reader r = w.reader()) { r.read(buf, 0, 0, buf[0].length); }
        if (buf[0].length != want24.length) return false;
        for (int i = 0; i < want24.length; i++) if (buf[0][i] != want24[i] << shift) return false;
        return true;
    }

    private static boolean sameAudio(Session a, Session b) throws IOException {
        try (AudioSource x = a.open(); AudioSource y = b.open()) {
            x.seek(0); y.seek(0);
            int[][] p = new int[a.channels][8192], q = new int[b.channels][8192];
            while (true) {
                int n = x.read(p, 0, 8192), m = y.read(q, 0, 8192);
                if (n != m) return false;
                if (n == 0) return true;
                for (int c = 0; c < a.channels; c++) if (!java.util.Arrays.equals(p[c], 0, n, q[c], 0, n)) return false;
            }
        }
    }
}
