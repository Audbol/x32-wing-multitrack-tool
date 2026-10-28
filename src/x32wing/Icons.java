package x32wing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;
import javax.swing.UIManager;

/** Small vector icons drawn on a 16x16 grid; they take the component's foreground so they follow the theme. */
final class Icons implements Icon {
    enum Kind { FOLDER, REFRESH, BOLT, EXPORT, METER, TAG, FLAG, PLUS, MINUS, SAVE, CANCEL, CHECKS, CLEAR, FILE, LINK, COMBINE, WRENCH }

    private final Kind kind;
    private final int size;
    private final Color color;

    Icons(Kind kind) { this(kind, 16, null); }
    Icons(Kind kind, int size, Color color) { this.kind = kind; this.size = size; this.color = color; }

    static Icons of(Kind k) { return new Icons(k); }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        g.scale(size / 16.0, size / 16.0);
        Color col = color != null ? color : c == null ? UIManager.getColor("Label.foreground")
                : c.isEnabled() ? c.getForeground() : UIManager.getColor("Label.disabledForeground");
        g.setColor(col);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double p = new Path2D.Double();
        switch (kind) {
            case FOLDER -> {
                p.moveTo(1.75, 4); p.lineTo(6, 4); p.lineTo(7.5, 5.5); p.lineTo(14.25, 5.5); p.lineTo(14.25, 13); p.lineTo(1.75, 13); p.closePath();
                g.draw(p);
            }
            case REFRESH -> {
                g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 40, 280, Arc2D.OPEN));
                p.moveTo(13.6, 1.8); p.lineTo(13.4, 5.6); p.lineTo(9.7, 5.2);
                g.draw(p);
            }
            case BOLT -> {
                p.moveTo(9.5, 1); p.lineTo(3, 9); p.lineTo(7.5, 9); p.lineTo(6.5, 15); p.lineTo(13, 6.8); p.lineTo(8.5, 6.8); p.closePath();
                g.fill(p);
            }
            case EXPORT -> {
                p.moveTo(2, 10); p.lineTo(2, 14); p.lineTo(14, 14); p.lineTo(14, 10);
                p.moveTo(8, 11); p.lineTo(8, 2);
                p.moveTo(4.5, 5.5); p.lineTo(8, 2); p.lineTo(11.5, 5.5);
                g.draw(p);
            }
            case METER -> {
                double[] h = {5, 9, 12, 7};
                for (int i = 0; i < 4; i++) g.fill(new RoundRectangle2D.Double(1.5 + i * 3.5, 14.5 - h[i], 2.4, h[i], 1.2, 1.2));
            }
            case TAG -> {
                p.moveTo(1.75, 1.75); p.lineTo(8, 1.75); p.lineTo(14.25, 8); p.lineTo(8, 14.25); p.lineTo(1.75, 8); p.closePath();
                g.draw(p);
                g.fill(new java.awt.geom.Ellipse2D.Double(4, 4, 2.6, 2.6));
            }
            case FLAG -> {
                g.draw(new Line2D.Double(3, 15, 3, 1.5));
                p.moveTo(3, 2); p.lineTo(13, 2); p.lineTo(10.5, 5.5); p.lineTo(13, 9); p.lineTo(3, 9);
                g.draw(p);
            }
            case PLUS -> { g.draw(new Line2D.Double(8, 3, 8, 13)); g.draw(new Line2D.Double(3, 8, 13, 8)); }
            case MINUS -> g.draw(new Line2D.Double(3, 8, 13, 8));
            case CANCEL -> { g.draw(new Line2D.Double(4, 4, 12, 12)); g.draw(new Line2D.Double(12, 4, 4, 12)); }
            case SAVE -> {
                p.moveTo(2, 2); p.lineTo(11.5, 2); p.lineTo(14, 4.5); p.lineTo(14, 14); p.lineTo(2, 14); p.closePath();
                g.draw(p);
                g.draw(new RoundRectangle2D.Double(5, 9, 6, 5, 1, 1));
                g.draw(new Line2D.Double(5, 2.5, 5, 5.5)); g.draw(new Line2D.Double(5, 5.5, 10, 5.5)); g.draw(new Line2D.Double(10, 5.5, 10, 2.5));
            }
            case CHECKS -> {
                g.draw(new RoundRectangle2D.Double(2, 2, 12, 12, 3, 3));
                p.moveTo(4.8, 8.2); p.lineTo(7, 10.4); p.lineTo(11.2, 5.8);
                g.draw(p);
            }
            case CLEAR -> g.draw(new RoundRectangle2D.Double(2, 2, 12, 12, 3, 3));
            case FILE -> {
                p.moveTo(3, 1.75); p.lineTo(9.5, 1.75); p.lineTo(13, 5.25); p.lineTo(13, 14.25); p.lineTo(3, 14.25); p.closePath();
                p.moveTo(9.5, 1.75); p.lineTo(9.5, 5.25); p.lineTo(13, 5.25);
                g.draw(p);
            }
            case LINK -> {
                g.draw(new RoundRectangle2D.Double(1.5, 5.5, 7.5, 5, 5, 5));
                g.draw(new RoundRectangle2D.Double(7, 5.5, 7.5, 5, 5, 5));
            }
            case COMBINE -> {
                g.draw(new RoundRectangle2D.Double(1.75, 2, 5.5, 12, 2, 2));
                g.draw(new RoundRectangle2D.Double(8.75, 2, 5.5, 12, 2, 2));
            }
            case WRENCH -> {
                p.moveTo(10.5, 1.8); p.curveTo(8.3, 1.4, 6.5, 3.4, 7.3, 5.6); p.lineTo(2, 10.9); p.lineTo(5.1, 14); p.lineTo(10.4, 8.7);
                p.curveTo(12.6, 9.5, 14.6, 7.7, 14.2, 5.5); p.lineTo(12.3, 7.3); p.lineTo(10.3, 6.9); p.lineTo(9.9, 4.9); p.closePath();
                g.draw(p);
            }
        }
        g.dispose();
    }
}
