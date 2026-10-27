package x32wing;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A multichannel stream of 32-bit left-justified integer samples. */
public interface AudioSource extends Closeable {
    int channels();
    long frames();
    int sampleRate();
    void seek(long frame) throws IOException;
    /** Fills dst[chOff + c][0..n). Returns n; fewer than max only at the end of the stream. */
    int read(int[][] dst, int chOff, int max) throws IOException;

    /** Opens a mono/stereo/multichannel WAV or FLAC file. */
    static AudioSource openFile(Path p) throws IOException {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".flac")) return new FlacSource(p);
        return new WavChain(List.of(WavFile.open(p)));
    }

    /** Consecutive WAV takes of one recording, played back as one continuous stream. */
    final class WavChain implements AudioSource {
        private final List<WavFile> takes;
        private final long[] starts;
        private final long total;
        private int cur = -1;
        private WavFile.Reader reader;
        private long pos;

        public WavChain(List<WavFile> takes) {
            this.takes = takes;
            starts = new long[takes.size()];
            long t = 0;
            for (int i = 0; i < takes.size(); i++) { starts[i] = t; t += takes.get(i).frames(); }
            total = t;
        }

        public int channels() { return takes.get(0).channels; }
        public long frames() { return total; }
        public int sampleRate() { return takes.get(0).sampleRate; }

        public void seek(long frame) throws IOException {
            pos = Math.max(0, Math.min(frame, total));
            int t = 0;
            while (t + 1 < takes.size() && starts[t + 1] <= pos) t++;
            open(t);
            reader.seek(pos - starts[t]);
        }

        private void open(int t) throws IOException {
            if (t == cur && reader != null) return;
            if (reader != null) reader.close();
            cur = t;
            reader = takes.get(t).reader();
        }

        public int read(int[][] dst, int chOff, int max) throws IOException {
            if (reader == null) seek(pos);
            int done = 0;
            while (done < max && pos < total) {
                if (pos >= starts[cur] + takes.get(cur).frames()) { open(cur + 1); reader.seek(0); }
                int r = reader.read(dst, chOff, done, max - done);
                if (r == 0) {
                    if (cur + 1 >= takes.size()) break;
                    open(cur + 1); reader.seek(0);
                    continue;
                }
                done += r; pos += r;
            }
            return done;
        }

        public void close() throws IOException { if (reader != null) reader.close(); reader = null; cur = -1; }
    }

    /** Side-by-side concatenation of channels from several sources (e.g. WING dual-card, or build inputs). Shorter sources are padded with silence. */
    final class Concat implements AudioSource {
        private final List<AudioSource> parts;
        private final int channels;
        private final long frames;
        private long pos;

        public Concat(List<AudioSource> parts) {
            this.parts = new ArrayList<>(parts);
            int c = 0; long f = 0;
            for (AudioSource s : parts) { c += s.channels(); f = Math.max(f, s.frames()); }
            channels = c; frames = f;
        }

        public int channels() { return channels; }
        public long frames() { return frames; }
        public int sampleRate() { return parts.get(0).sampleRate(); }

        public void seek(long frame) throws IOException {
            pos = Math.max(0, Math.min(frame, frames));
            for (AudioSource s : parts) s.seek(Math.min(pos, s.frames()));
        }

        public int read(int[][] dst, int chOff, int max) throws IOException {
            int n = (int) Math.min(max, frames - pos);
            int c = chOff;
            for (AudioSource s : parts) {
                int got = 0;
                while (got < n) {
                    int r = got == 0 ? s.read(dst, c, n) : readInto(s, dst, c, got, n - got);
                    if (r <= 0) break;
                    got += r;
                }
                for (int k = 0; k < s.channels(); k++) Arrays.fill(dst[c + k], got, n, 0);
                c += s.channels();
            }
            pos += n;
            return n;
        }

        private static int readInto(AudioSource s, int[][] dst, int chOff, int off, int max) throws IOException {
            int[][] view = offsetView(dst, chOff, s.channels(), max);
            int r = s.read(view, 0, max);
            copyBack(view, dst, chOff, off, r);
            return r;
        }

        public void close() throws IOException { for (AudioSource s : parts) s.close(); }
    }

    /** Decodes a FLAC file. Seeking decodes from the start (only used for builds, which read sequentially). */
    final class FlacSource implements AudioSource {
        private final Path path;
        private FlacDecoder dec;
        private int[][] frameBuf;
        private int bufN, bufPos;
        private long pos;
        private final int channels, rate, shift;
        private final long frames;

        public FlacSource(Path p) throws IOException {
            path = p;
            dec = new FlacDecoder(p);
            channels = dec.channels; rate = dec.sampleRate; frames = dec.totalSamples; shift = 32 - dec.bitsPerSample;
            frameBuf = new int[channels][dec.maxBlockSize];
        }

        public int channels() { return channels; }
        public long frames() { return frames; }
        public int sampleRate() { return rate; }

        public void seek(long frame) throws IOException {
            if (frame < pos) { dec.close(); dec = new FlacDecoder(path); pos = 0; bufN = bufPos = 0; }
            int[][] scratch = new int[channels][4096];
            while (pos < frame) { int r = read(scratch, 0, (int) Math.min(4096, frame - pos)); if (r == 0) break; }
        }

        public int read(int[][] dst, int chOff, int max) throws IOException {
            int done = 0;
            while (done < max) {
                if (bufPos >= bufN) {
                    bufN = dec.readFrame(frameBuf); bufPos = 0;
                    if (bufN <= 0) { bufN = 0; break; }
                }
                int n = Math.min(max - done, bufN - bufPos);
                for (int c = 0; c < channels; c++) {
                    int[] s = frameBuf[c], d = dst[chOff + c];
                    for (int i = 0; i < n; i++) d[done + i] = s[bufPos + i] << shift;
                }
                bufPos += n; done += n;
            }
            pos += done;
            return done;
        }

        public void close() throws IOException { dec.close(); }
    }

    private static int[][] offsetView(int[][] dst, int chOff, int nc, int len) {
        return new int[nc][len];
    }

    private static void copyBack(int[][] view, int[][] dst, int chOff, int off, int n) {
        for (int c = 0; c < view.length; c++) System.arraycopy(view[c], 0, dst[chOff + c], off, n);
    }
}
