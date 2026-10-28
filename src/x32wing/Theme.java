package x32wing;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.swing.UIManager;

/** Light/dark theme handling (FlatLaf), following the operating system setting by default. */
final class Theme {
    private Theme() {}

    enum Mode {
        SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark");
        final String label;
        Mode(String l) { label = l; }
    }

    static final Color GOOD = new Color(0x22C55E), WARN = new Color(0xF59E0B), HOT = new Color(0xEF4444);
    private static boolean dark;

    static boolean isDark() { return dark; }

    /** Installs the look and feel for the mode. Call on the EDT. */
    static void apply(Mode mode) {
        boolean d = mode == Mode.DARK || (mode == Mode.SYSTEM && systemIsDark());
        dark = d;
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#3B82F6"));
        if (d) FlatMacDarkLaf.setup(); else FlatMacLightLaf.setup();
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("CheckBox.arc", 4);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 0));
        UIManager.put("Table.rowHeight", 28);
        UIManager.put("TableHeader.height", 30);
        UIManager.put("Table.selectionArc", 6);
        UIManager.put("Table.selectionInsets", new java.awt.Insets(0, 2, 0, 2));
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("SplitPane.dividerSize", 1);
        UIManager.put("SplitPaneDivider.style", "plain");
        UIManager.put("List.selectionArc", 0);
        UIManager.put("List.selectionInsets", new java.awt.Insets(0, 0, 0, 0));
        UIManager.put("TitlePane.unifiedBackground", true);
        Color bg = UIManager.getColor("Table.background");
        UIManager.put("Table.alternateRowColor", d ? mix(bg, Color.WHITE, 0.03) : mix(bg, Color.BLACK, 0.025));
    }

    static Color muted() { return UIManager.getColor("Label.disabledForeground"); }

    /** Background for panels that should stand out slightly from the window (sidebar, export bar). */
    static Color panelAlt() {
        Color bg = UIManager.getColor("Panel.background");
        return dark ? mix(bg, Color.BLACK, 0.18) : mix(bg, Color.WHITE, 0.55);
    }

    static Color border() { return UIManager.getColor("Component.borderColor"); }

    static Color mix(Color a, Color b, double t) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Reads the OS dark-mode setting (Windows app theme, macOS appearance, GTK theme name). */
    static boolean systemIsDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme");
                return out.contains("0x0");
            }
            if (os.contains("mac")) return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
            // Linux / BSD: GTK_THEME override, GNOME 42+ colour scheme, GTK theme name, then KDE's colour scheme.
            String gtk = System.getenv("GTK_THEME");
            if (gtk != null && !gtk.isBlank()) return gtk.toLowerCase(Locale.ROOT).contains("dark");
            String scheme = tryRun("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme.contains("prefer-dark")) return true;
            if (scheme.contains("prefer-light")) return false;
            if (tryRun("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT).contains("dark")) return true;
            java.nio.file.Path kde = java.nio.file.Path.of(System.getProperty("user.home"), ".config", "kdeglobals");
            if (java.nio.file.Files.isReadable(kde))
                for (String l : java.nio.file.Files.readAllLines(kde))
                    if (l.startsWith("ColorScheme=")) return l.toLowerCase(Locale.ROOT).contains("dark");
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String tryRun(String... cmd) {
        try { return run(cmd); } catch (Exception e) { return ""; }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor(3, TimeUnit.SECONDS);
        return sb.toString();
    }
}
