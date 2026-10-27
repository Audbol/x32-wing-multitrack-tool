package x32wing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Marker lists and DAW project files. */
final class Exports {
    private Exports() {}

    static void writeMarkersCsv(Path p, List<Long> markers, int rate) throws IOException {
        StringBuilder sb = new StringBuilder("marker,time,seconds,samples\n");
        for (int i = 0; i < markers.size(); i++) {
            double t = markers.get(i) / (double) rate;
            sb.append(String.format(Locale.ROOT, "%d,%s,%.3f,%d\n", i + 1, Util.hmsMillis(t), t, markers.get(i)));
        }
        Files.writeString(p, sb);
    }

    static void writeAudacityLabels(Path p, List<Long> markers, int rate) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < markers.size(); i++) {
            double t = markers.get(i) / (double) rate;
            sb.append(String.format(Locale.ROOT, "%.6f\t%.6f\tMarker %d\n", t, t, i + 1));
        }
        Files.writeString(p, sb);
    }

    /** REAPER project referencing the exported files (relative paths) with markers. */
    static void writeReaper(Path p, List<ExportJob.TrackWriter> tracks, int rate, long frames, List<Long> markers) throws IOException {
        double len = frames / (double) rate;
        StringBuilder sb = new StringBuilder();
        sb.append("<REAPER_PROJECT 0.1 \"6.0\" 0\n");
        sb.append("  SAMPLERATE ").append(rate).append(" 0 0\n");
        for (int i = 0; i < markers.size(); i++)
            sb.append(String.format(Locale.ROOT, "  MARKER %d %.6f \"\" 0\n", i + 1, markers.get(i) / (double) rate));
        for (ExportJob.TrackWriter t : tracks) {
            String file = p.getParent().relativize(t.path).toString();
            String type = file.toLowerCase().endsWith(".flac") ? "FLAC" : "WAVE";
            String name = q(t.track.name());
            sb.append("  <TRACK\n    NAME ").append(name).append('\n')
              .append("    NCHAN 2\n")
              .append("    <ITEM\n      POSITION 0\n")
              .append(String.format(Locale.ROOT, "      LENGTH %.6f\n", len))
              .append("      NAME ").append(name).append('\n')
              .append("      <SOURCE ").append(type).append("\n        FILE ").append(q(file)).append("\n      >\n")
              .append("    >\n  >\n");
        }
        sb.append(">\n");
        Files.writeString(p, sb);
    }

    private static String q(String s) {
        if (!s.contains("\"")) return "\"" + s + "\"";
        if (!s.contains("'")) return "'" + s + "'";
        return "`" + s.replace('`', '\'') + "`";
    }
}
