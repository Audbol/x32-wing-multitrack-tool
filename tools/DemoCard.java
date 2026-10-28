package x32wing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a demo X-Live card (synthetic band recording, 32 ch, markers, channel-name list) used for the
 * README screenshots. Run against build\classes: java -cp build\classes;<out> x32wing.DemoCard <dir> [seconds]
 */
public class DemoCard {
    static final String[] NAMES = {"Kick In", "Kick Out", "Snare Top", "Snare Bot", "Hi-Hat", "Rack Tom", "Floor Tom", "OH L", "OH R",
            "Bass DI", "Bass Amp", "Gtr 1", "Gtr 2", "Keys L", "Keys R", "Acoustic", "Lead Vox", "BV 1", "BV 2", "BV 3",
            "Room L", "Room R", "Talkback", "Click", "", "", "", "", "", "", "Main L", "Main R"};
    /** Peak level per channel in dBFS; NaN = unused input (preamp noise only). */
    static final double[] LEVEL = {-6, -9, -4, -14, -18, -10, -8, -12, -12, -7, -11, -13, -15, -16, -16, -12, -3, -10, -11, -24,
            -20, -20, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -2, -2};

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        int secs = a.length > 1 ? Integer.parseInt(a[1]) : 90, rate = 48000, n = secs * rate;
        Files.createDirectories(dir.resolve("src"));
        Random r = new Random(7);
        List<Path> files = new ArrayList<>();
        for (int c = 0; c < 32; c++) {
            int[] x = new int[n];
            double lvl = LEVEL[c];
            double f = 60 + 90 * c, amp = Double.isNaN(lvl) ? 0 : Math.pow(10, lvl / 20) * 8388607 * 0.8;
            for (int i = 0; i < n; i++) {
                double t = i / (double) rate;
                double beat = Math.exp(-((t * 2) % 1) * (c < 7 ? 9 : 2));
                double v = Double.isNaN(lvl) ? r.nextGaussian() * 2.5
                        : amp * (c < 7 ? beat : 0.55 + 0.45 * Math.sin(t * 0.7 + c)) * Math.sin(2 * Math.PI * f * t) + r.nextGaussian() * 40;
                x[i] = (int) Math.max(-8388608, Math.min(8388607, v));
            }
            Path p = dir.resolve("src").resolve(String.format("%02d.wav", c + 1));
            try (WavWriter w = new WavWriter(p, 1, rate, 24, false)) { w.write(new int[][] {x}, n); }
            files.add(p);
        }
        new XLiveBuilder().build(files, dir.resolve("card"), "Spring Tour", List.of(secs * 0.22, secs * 0.47, secs * 0.71), null);
        StringBuilder sb = new StringBuilder();
        for (String s : NAMES) sb.append(s).append(System.lineSeparator());
        Files.writeString(dir.resolve("names.txt"), sb);
        for (Path p : files) Files.delete(p);
        Files.delete(dir.resolve("src"));
    }
}
