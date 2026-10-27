import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Draws the app icon (stacked multitrack waveform lanes) and writes a multi-size .ico. Run: java tools/MakeIcon.java out.ico */
public class MakeIcon {
    static final Color[] LANES = {new Color(0xFF5A5F), new Color(0xFFB347), new Color(0x4CD68B), new Color(0x4AA8FF)};

    public static void main(String[] a) throws Exception {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(draw(s), "png", o);
            pngs.add(o.toByteArray());
        }
        ByteArrayOutputStream ico = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int off = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            h.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0)
             .putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(off);
            off += pngs.get(i).length;
        }
        ico.write(h.array());
        for (byte[] p : pngs) ico.write(p);
        Files.write(Path.of(a[0]), ico.toByteArray());
        if (a.length > 1) ImageIO.write(draw(512), "png", Path.of(a[1]).toFile());
    }

    static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double m = s * 0.04, r = s * 0.22;
        g.setPaint(new GradientPaint(0, 0, new Color(0x2B2F3A), 0, s, new Color(0x14161C)));
        g.fill(new RoundRectangle2D.Double(m, m, s - 2 * m, s - 2 * m, r, r));
        int lanes = LANES.length;
        double top = s * 0.17, h = (s - 2 * top) / lanes, left = s * 0.15, right = s * 0.85;
        g.setStroke(new BasicStroke((float) Math.max(1.2, s * 0.035), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 0; k < lanes; k++) {
            double cy = top + h * (k + 0.5), amp = h * 0.38;
            Path2D.Double p = new Path2D.Double();
            int steps = Math.max(12, s / 3);
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps, x = left + t * (right - left);
                double env = Math.sin(Math.PI * t) * (0.55 + 0.45 * Math.sin(t * 7 + k * 1.3));
                double y = cy + amp * env * Math.sin(t * (14 + 3 * k) * Math.PI);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            g.setColor(LANES[k]);
            g.draw(p);
        }
        g.dispose();
        return img;
    }
}
