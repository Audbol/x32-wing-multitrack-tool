package x32wing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/** Swing front end (FlatLaf). */
final class Gui extends JFrame {
    private static final String APP = "X32 / M32 / WING Multitrack Tool";
    private static final Preferences PREFS = Preferences.userNodeForPackage(Gui.class);
    private final Preferences prefs = PREFS;

    private final DefaultListModel<Session> sessions = new DefaultListModel<>();
    private final JList<Session> sessionList = new JList<>(sessions);
    private final JLabel sourceLabel = new JLabel();
    private final JLabel countLabel = new JLabel();
    private final JLabel sidebarTip = new JLabel("<html>Tip: Ctrl-click two recordings, then use<br>Tools › Combine for WING cards A + B.</html>");
    private JComponent exportBar;
    private final JScrollPane sidebarScroll = new JScrollPane(sessionList);
    private final Map<Session, boolean[][]> selection = new HashMap<>(); // [0]=export, [1]=stereo with next

    private final CardLayout mainCards = new CardLayout();
    private final JPanel main = new JPanel(mainCards);
    private final JLabel title = new JLabel(), subtitle = new JLabel();
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

    private final ChannelModel chModel = new ChannelModel();
    private final JTable chTable = new JTable(chModel);
    private final JLabel chSummary = new JLabel();
    private final MarkerModel mkModel = new MarkerModel();
    private final JTable mkTable = new JTable(mkModel);
    private final JTextField sessionName = new JTextField(18);
    private final JPanel details = new JPanel(new GridBagLayout());
    private final TakeModel takeModel = new TakeModel();
    private final JTable takeTable = new JTable(takeModel);
    private final JTabbedPane tabs = new JTabbedPane();

    private final JComboBox<ExportJob.Format> format = new JComboBox<>(ExportJob.Format.values());
    private final JCheckBox dither = new JCheckBox("TPDF dither", true);
    private final JComboBox<String> range = new JComboBox<>(new String[] {"Whole recording", "Split at markers", "Custom range"});
    private final JTextField rangeStart = new JTextField("0:00", 6), rangeEnd = new JTextField("", 6);
    private final JComboBox<ExportJob.Normalize> normalize = new JComboBox<>(ExportJob.Normalize.values());
    private final JSpinner normDb = new JSpinner(new SpinnerNumberModel(-1.0, -40.0, 0.0, 0.5));
    private final JCheckBox removeSilent = new JCheckBox("Remove silent channels below");
    private final JSpinner silentDb = new JSpinner(new SpinnerNumberModel(-60.0, -120.0, -10.0, 1.0));
    private final JTextField outDir = new JTextField(26);
    private final JCheckBox reaper = new JCheckBox("REAPER project", true);
    private final JCheckBox markerFiles = new JCheckBox("Marker lists", true);

    private final JButton exportBtn = new JButton("Export selected", Icons.of(Icons.Kind.EXPORT));
    private final JButton quickBtn = new JButton("Quick 16-bit FLAC", new Icons(Icons.Kind.BOLT, 16, Color.WHITE));
    private final JButton cancelBtn = new JButton("Cancel");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel("Ready");
    private final List<JComponent> busyDisabled = new ArrayList<>();
    private volatile boolean cancel;
    private boolean busy;
    private Path lastSource;
    private Theme.Mode themeMode;

    static void launch() {
        SwingUtilities.invokeLater(() -> {
            Theme.Mode m = savedTheme();
            Theme.apply(m);
            Gui g = new Gui(m);
            g.setVisible(true);
        });
    }

    private static Theme.Mode savedTheme() {
        try { return Theme.Mode.valueOf(PREFS.get("theme", "SYSTEM")); } catch (IllegalArgumentException e) { return Theme.Mode.SYSTEM; }
    }

    /** Renders the window with a recording loaded to a PNG (for docs / design review). */
    static void screenshot(Path out, Path source, Path names, boolean analyze, int width, int height, int tab, String theme) throws Exception {
        Gui[] g = new Gui[1];
        String[] th = theme == null ? new String[] {"system"} : theme.split(">"); // "light>dark" tests a live switch
        Theme.Mode mode = Theme.Mode.valueOf(th[0].toUpperCase(Locale.ROOT));
        Theme.Mode switchTo = th.length > 1 ? Theme.Mode.valueOf(th[1].toUpperCase(Locale.ROOT)) : null;
        List<Session> found = source == null ? List.of() : Session.scan(source, new ArrayList<>());
        if (!found.isEmpty()) {
            Session s = found.get(found.size() - 1);
            if (names != null) Cli.applyNames(s, NameImport.read(names));
            if (analyze) s.peaks = ExportJob.analyzeSession(s, null);
        }
        SwingUtilities.invokeAndWait(() -> {
            Theme.apply(mode);
            g[0] = new Gui(mode);
            g[0].setSize(width, height);
            g[0].setLocation(0, 0);
            g[0].setVisible(true);
            if (source != null) g[0].showSessions(source, found);
            if (!found.isEmpty()) g[0].sessionList.setSelectedIndex(found.size() - 1);
            g[0].tabs.setSelectedIndex(tab);
        });
        if (switchTo != null) {
            Thread.sleep(800);
            SwingUtilities.invokeAndWait(() -> { g[0].themeMode = switchTo; g[0].reapplyTheme(); });
        }
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            JRootPane rp = g[0].getRootPane();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(rp.getWidth(), rp.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D gr = img.createGraphics();
            rp.paint(gr);
            gr.dispose();
            try { javax.imageio.ImageIO.write(img, "png", out.toFile()); } catch (IOException e) { throw new RuntimeException(e); }
            g[0].dispose();
        });
    }

    private Gui(Theme.Mode mode) {
        super(APP);
        themeMode = mode;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        java.net.URL icon = Gui.class.getResource("icon.png");
        if (icon != null) {
            Image img = new ImageIcon(icon).getImage();
            setIconImages(List.of(img.getScaledInstance(16, 16, Image.SCALE_SMOOTH), img.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                    img.getScaledInstance(64, 64, Image.SCALE_SMOOTH), img));
        }
        setJMenuBar(menu());
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, true);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar(), mainArea());
        split.setDividerLocation(300);
        split.setBorder(null);
        split.setContinuousLayout(true);

        JPanel root = new JPanel(new BorderLayout());
        root.add(header(), BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        exportBar = exportPanel();
        root.add(exportBar, BorderLayout.SOUTH);
        setContentPane(root);
        installDrop(root);
        alignAllHeaders();
        loadPrefs();
        setMinimumSize(new Dimension(1080, 720));
        setSize(1320, 860);
        setLocationRelativeTo(null);
        showSession();
        addWindowListener(new WindowAdapter() {
            @Override public void windowActivated(WindowEvent e) { recheckSystemTheme(); }
        });
    }

    // ---------------------------------------------------------------------------------------- layout

    private JMenuBar menu() {
        JMenuBar mb = new JMenuBar();
        JMenu f = new JMenu("File");
        f.add(item("Open card or folder…", Icons.Kind.FOLDER, this::chooseSource, KeyStroke.getKeyStroke("control O")));
        f.add(item("Rescan", Icons.Kind.REFRESH, this::rescan, KeyStroke.getKeyStroke("F5")));
        f.addSeparator();
        f.add(item("Exit", null, () -> System.exit(0), null));
        JMenu t = new JMenu("Tools");
        t.add(item("Build card session for virtual soundcheck…", Icons.Kind.FILE, this::buildDialog, null));
        t.add(item("Combine selected recordings (WING card A + B)", Icons.Kind.COMBINE, this::combineSelected, null));
        t.add(item("Repair unfinalized recordings…", Icons.Kind.WRENCH, this::repairHeaders, null));
        t.addSeparator();
        t.add(item("Run self-test", null, this::selfTest, null));
        JMenu v = new JMenu("View");
        ButtonGroup grp = new ButtonGroup();
        for (Theme.Mode m : Theme.Mode.values()) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(m.label, m == themeMode);
            r.addActionListener(e -> switchTheme(m));
            grp.add(r);
            v.add(r);
        }
        JMenu h = new JMenu("Help");
        h.add(item("Command line usage", null, () -> {
            JTextArea a = new JTextArea(Cli.USAGE);
            a.setEditable(false);
            a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane sp = new JScrollPane(a);
            sp.setPreferredSize(new Dimension(760, 520));
            JOptionPane.showMessageDialog(this, sp, "Command line", JOptionPane.PLAIN_MESSAGE);
        }, null));
        h.add(item("Project page", null, () -> browse("https://github.com/Audbol/x32-wing-multitrack-tool"), null));
        h.addSeparator();
        h.add(item("About", null, this::about, null));
        mb.add(f); mb.add(t); mb.add(v); mb.add(h);
        return mb;
    }

    private static JMenuItem item(String label, Icons.Kind icon, Runnable r, KeyStroke key) {
        JMenuItem i = new JMenuItem(label, icon == null ? null : Icons.of(icon));
        if (key != null) i.setAccelerator(key);
        i.addActionListener(e -> r.run());
        return i;
    }

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.border()), new EmptyBorder(10, 14, 10, 14)));
        JButton open = new JButton("Open card or folder…", Icons.of(Icons.Kind.FOLDER));
        open.addActionListener(e -> chooseSource());
        JButton rescan = new JButton(Icons.of(Icons.Kind.REFRESH));
        rescan.setToolTipText("Rescan (F5)");
        rescan.addActionListener(e -> rescan());
        open.setFocusable(false);
        rescan.setFocusable(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(open); left.add(rescan);
        left.add(Box.createHorizontalStrut(8));
        sourceLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        left.add(sourceLabel);
        p.add(left, BorderLayout.WEST);
        busyDisabled.add(open); busyDisabled.add(rescan);
        return p;
    }

    private JComponent sidebar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.panelAlt());
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(14, 16, 6, 16));
        JLabel cap = new JLabel("RECORDINGS");
        cap.putClientProperty(FlatClientProperties.STYLE, "font: -2 bold; foreground: $Label.disabledForeground");
        countLabel.putClientProperty(FlatClientProperties.STYLE, "font: -2 bold; foreground: $Label.disabledForeground");
        head.add(cap, BorderLayout.WEST);
        head.add(countLabel, BorderLayout.EAST);
        sessionList.setCellRenderer(new SessionRenderer());
        sessionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionList.setBackground(Theme.panelAlt());
        sessionList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSession(); });
        JScrollPane sp = sidebarScroll;
        sp.setBorder(null);
        sp.getViewport().setBackground(Theme.panelAlt());
        JLabel hint = sidebarTip;
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        hint.setBorder(new EmptyBorder(8, 16, 12, 16));
        hint.setVisible(false);
        p.add(head, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(hint, BorderLayout.SOUTH);
        p.setMinimumSize(new Dimension(240, 100));
        return p;
    }

    private JComponent mainArea() {
        main.add(emptyState(), "empty");
        JPanel sess = new JPanel(new BorderLayout());
        JPanel head = new JPanel(new GridBagLayout());
        head.setBorder(new EmptyBorder(16, 20, 6, 20));
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        subtitle.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        chips.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.anchor = GridBagConstraints.WEST; g.weightx = 1;
        g.gridy = 0; head.add(title, g);
        g.gridy = 1; g.insets = new Insets(2, 0, 0, 0); head.add(subtitle, g);
        g.gridy = 2; g.insets = new Insets(10, -6, 0, 0); head.add(chips, g);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_INSETS, new Insets(0, 14, 0, 14));
        tabs.setBorder(new EmptyBorder(0, 14, 0, 14));
        tabs.addTab("Channels", channelsTab());
        tabs.addTab("Markers", markersTab());
        tabs.addTab("Details", detailsTab());
        sess.add(head, BorderLayout.NORTH);
        sess.add(tabs, BorderLayout.CENTER);
        main.add(sess, "session");
        return main;
    }

    private JComponent emptyState() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.insets = new Insets(6, 0, 6, 0);
        java.net.URL icon = Gui.class.getResource("icon.png");
        if (icon != null) p.add(new JLabel(new ImageIcon(new ImageIcon(icon).getImage().getScaledInstance(112, 112, Image.SCALE_SMOOTH))), g);
        JLabel h = new JLabel("Open an X-Live or WING-LIVE recording");
        h.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        g.insets = new Insets(18, 0, 4, 0);
        p.add(h, g);
        JLabel s = new JLabel("<html><center>Insert the SD card and choose it, or drag the card, an X_LIVE folder<br>or a multichannel WAV file onto this window.</center></html>");
        s.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground; font: +1");
        g.insets = new Insets(0, 0, 18, 0);
        p.add(s, g);
        JButton open = new JButton("Open card or folder…", Icons.of(Icons.Kind.FOLDER));
        open.setIcon(new Icons(Icons.Kind.FOLDER, 16, Color.WHITE));
        open.putClientProperty(FlatClientProperties.STYLE,
                "font: +1 bold; margin: 8,20,8,20; background: $Component.accentColor; foreground: #ffffff; borderWidth: 0; focusWidth: 0");
        open.addActionListener(e -> chooseSource());
        busyDisabled.add(open);
        p.add(open, g);
        return p;
    }

    private JButton toolButton(String label, Icons.Kind icon, String tip, Runnable r) {
        JButton b = new JButton(label, icon == null ? null : Icons.of(icon));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.putClientProperty(FlatClientProperties.STYLE, "margin: 4,8,4,8");
        if (tip != null) b.setToolTipText(tip);
        b.addActionListener(e -> r.run());
        busyDisabled.add(b);
        return b;
    }

    private JComponent channelsTab() {
        chTable.setFillsViewportHeight(true);
        chTable.setShowGrid(false);
        chTable.getTableHeader().setReorderingAllowed(false);
        chTable.putClientProperty(FlatClientProperties.STYLE, "rowHeight: 28");
        TableColumn c0 = chTable.getColumnModel().getColumn(0), c1 = chTable.getColumnModel().getColumn(1), c3 = chTable.getColumnModel().getColumn(3),
                c4 = chTable.getColumnModel().getColumn(4);
        c0.setMaxWidth(60); c0.setMinWidth(60);
        c1.setMaxWidth(48); c1.setMinWidth(48);
        c3.setMaxWidth(72); c3.setMinWidth(72);
        c4.setMinWidth(220); c4.setPreferredWidth(260);
        DefaultTableCellRenderer num = new DefaultTableCellRenderer();
        num.setHorizontalAlignment(SwingConstants.CENTER);
        c1.setCellRenderer(new MutedRenderer(SwingConstants.CENTER));
        chTable.getColumnModel().getColumn(2).setCellRenderer(new NameRenderer());
        c4.setCellRenderer(new MeterRenderer());

        JPanel bar1 = new JPanel(new BorderLayout());
        bar1.setBorder(new EmptyBorder(8, 4, 6, 4));
        JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        l.add(toolButton("All", Icons.Kind.CHECKS, "Tick every channel", () -> setAll(true)));
        l.add(toolButton("None", Icons.Kind.CLEAR, "Untick every channel", () -> setAll(false)));
        l.add(sep());
        l.add(toolButton("Analyze levels", Icons.Kind.METER, "Measure the peak level of every channel", this::analyze));
        l.add(toolButton("Select active", null, "Tick only channels with signal above the silence threshold", this::selectNonSilent));
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        r.add(toolButton("Import names…", Icons.Kind.TAG, "From an X32/M32 scene (.scn), WING snapshot (.snap) or a text list", this::importNames));
        r.add(toolButton("Save list…", Icons.Kind.SAVE, "Save the channel names to a text file", this::saveNames));
        r.add(toolButton("Recall last", null, "Use the names from your last export", this::recallNames));
        bar1.add(l, BorderLayout.WEST);
        bar1.add(r, BorderLayout.EAST);

        JScrollPane sp = new JScrollPane(chTable);
        sp.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
        chSummary.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        chSummary.setBorder(new EmptyBorder(6, 6, 8, 6));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(4, 6, 0, 6));
        p.add(bar1, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(chSummary, BorderLayout.SOUTH);
        return p;
    }

    /** Left-aligns table headers (FlatLaf centres them), except the given narrow columns. Re-run after a theme switch. */
    private static void alignHeaders(JTable t, int... centered) {
        TableCellRenderer base = t.getTableHeader().getDefaultRenderer();
        if (base instanceof HeaderAlign) return;
        t.getTableHeader().setDefaultRenderer(new HeaderAlign(base, centered));
    }

    private record HeaderAlign(TableCellRenderer base, int[] centered) implements TableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            Component comp = base.getTableCellRendererComponent(t, v, s, f, r, c);
            int mc = t.convertColumnIndexToModel(c);
            if (comp instanceof JLabel l) l.setHorizontalAlignment(Arrays.stream(centered).anyMatch(x -> x == mc) ? SwingConstants.CENTER : SwingConstants.LEADING);
            return comp;
        }
    }

    private void alignAllHeaders() {
        alignHeaders(chTable, 0, 1, 3);
        alignHeaders(mkTable, 0);
        alignHeaders(takeTable);
    }

    private static JComponent sep() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(9, 20));
        return s;
    }

    private JComponent markersTab() {
        mkTable.setFillsViewportHeight(true);
        mkTable.setShowGrid(false);
        mkTable.getColumnModel().getColumn(0).setMaxWidth(60);
        mkTable.getColumnModel().getColumn(0).setCellRenderer(new MutedRenderer(SwingConstants.CENTER));
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(10, 4, 8, 4));
        JPanel nm = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        nm.add(new JLabel("Session name"));
        sessionName.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "up to 15 characters");
        nm.add(sessionName);
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        r.add(toolButton("Add…", Icons.Kind.PLUS, "Add a marker at a time", this::addMarker));
        r.add(toolButton("Remove", Icons.Kind.MINUS, "Remove the selected markers", this::removeMarkers));
        r.add(sep());
        r.add(toolButton("Export list…", Icons.Kind.EXPORT, "Save as CSV, or Audacity labels (.txt)", this::exportMarkers));
        r.add(toolButton("Save to card", Icons.Kind.SAVE, "Write name and markers to SE_LOG.BIN (keeps a backup)", this::saveLog));
        top.add(nm, BorderLayout.WEST);
        top.add(r, BorderLayout.EAST);
        JScrollPane sp = new JScrollPane(mkTable);
        sp.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
        JLabel hint = new JLabel("Markers set on the console are read from the card. Use “Split at markers” in Export to get one folder per song.");
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        hint.setBorder(new EmptyBorder(6, 6, 8, 6));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(4, 6, 0, 6));
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private JComponent detailsTab() {
        details.setBorder(new EmptyBorder(14, 10, 10, 10));
        JTable takes = takeTable;
        takes.setShowGrid(false);
        takes.setFillsViewportHeight(true);
        takes.getColumnModel().getColumn(3).setCellRenderer(new StatusRenderer());
        JScrollPane sp = new JScrollPane(takes);
        sp.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
        JLabel cap = new JLabel("TAKE FILES");
        cap.putClientProperty(FlatClientProperties.STYLE, "font: -2 bold; foreground: $Label.disabledForeground");
        cap.setBorder(new EmptyBorder(10, 4, 6, 4));
        JPanel south = new JPanel(new BorderLayout());
        south.add(cap, BorderLayout.NORTH);
        south.add(sp, BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(4, 6, 8, 6));
        p.add(details, BorderLayout.NORTH);
        p.add(south, BorderLayout.CENTER);
        return p;
    }

    private JComponent exportPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.panelAlt());
        p.setBorder(BorderFactory.createCompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.border()), new EmptyBorder(12, 18, 14, 18)));

        JPanel cols = new JPanel(new GridBagLayout());
        cols.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(0, 0, 0, 28);
        g.gridy = 0;

        g.gridx = 0; cols.add(section("FORMAT", row(format), row(dither)), g);
        normDb.setPreferredSize(new Dimension(74, normDb.getPreferredSize().height));
        silentDb.setPreferredSize(new Dimension(74, silentDb.getPreferredSize().height));
        g.gridx = 1; cols.add(section("PROCESSING", row(new JLabel("Normalize"), normalize, normDb, muted("dBFS")),
                row(removeSilent, silentDb, muted("dBFS"))), g);
        g.gridx = 2; cols.add(section("RANGE", row(range), row(muted("from"), rangeStart, muted("to"), rangeEnd)), g);
        JButton browse = new JButton(Icons.of(Icons.Kind.FOLDER));
        browse.setToolTipText("Choose output folder");
        browse.addActionListener(e -> chooseOut());
        JButton openOut = new JButton(Icons.of(Icons.Kind.EXPORT));
        openOut.setToolTipText("Open output folder");
        openOut.addActionListener(e -> openFolder(Path.of(outDir.getText().trim())));
        g.gridx = 3; g.weightx = 1; g.insets = new Insets(0, 0, 0, 0);
        cols.add(section("OUTPUT", row(outDir, browse, openOut), row(reaper, markerFiles)), g);

        quickBtn.putClientProperty(FlatClientProperties.STYLE,
                "background: $Component.accentColor; foreground: #ffffff; font: bold; margin: 7,18,7,18; borderWidth: 0; focusWidth: 0;"
                        + "disabledBackground: fade($Component.accentColor,40%); disabledText: #ffffffcc");
        quickBtn.setToolTipText("Every channel of the selected recording(s) to its own dithered 16-bit FLAC. Normalize and silent-channel settings apply.");
        exportBtn.putClientProperty(FlatClientProperties.STYLE, "margin: 7,14,7,14");
        exportBtn.setToolTipText("Export the ticked channels with the settings above");
        cancelBtn.putClientProperty(FlatClientProperties.STYLE, "margin: 7,14,7,14");
        exportBtn.addActionListener(e -> export(false));
        quickBtn.addActionListener(e -> export(true));
        cancelBtn.addActionListener(e -> cancel = true);
        bar.putClientProperty(FlatClientProperties.STYLE, "arc: 999");
        bar.setPreferredSize(new Dimension(220, 6));
        status.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

        JPanel progress = new JPanel(new GridBagLayout());
        progress.setOpaque(false);
        GridBagConstraints q = new GridBagConstraints();
        q.gridx = 0; q.anchor = GridBagConstraints.WEST; q.fill = GridBagConstraints.HORIZONTAL; q.weightx = 1;
        q.gridy = 0; progress.add(status, q);
        q.gridy = 1; q.insets = new Insets(6, 0, 0, 120); progress.add(bar, q);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelBtn); buttons.add(exportBtn); buttons.add(quickBtn);
        JPanel actions = new JPanel(new BorderLayout(16, 0));
        actions.setOpaque(false);
        actions.setBorder(new EmptyBorder(14, 0, 0, 0));
        actions.add(progress, BorderLayout.CENTER);
        actions.add(buttons, BorderLayout.EAST);

        p.add(cols, BorderLayout.CENTER);
        p.add(actions, BorderLayout.SOUTH);

        normDb.setToolTipText("Peak level every file is normalized to");
        silentDb.setToolTipText("Channels whose loudest peak stays below this are treated as unused and not exported");
        removeSilent.setToolTipText(silentDb.getToolTipText());
        normalize.setToolTipText("Each file: every track peaks at the target. All files, same gain: the loudest track hits the target and the balance is kept.");
        rangeStart.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "0:00");
        range.addActionListener(e -> updateButtons());
        normalize.addActionListener(e -> updateButtons());
        removeSilent.addActionListener(e -> { updateButtons(); chTable.repaint(); updateSummary(); });
        silentDb.addChangeListener(e -> { chTable.repaint(); updateSummary(); });
        format.addActionListener(e -> updateButtons());
        busyDisabled.addAll(List.of(format, dither, range, rangeStart, rangeEnd, normalize, normDb, removeSilent, silentDb, outDir, browse,
                reaper, markerFiles, exportBtn, quickBtn));
        return p;
    }

    private static JComponent section(String caption, JComponent... rows) {
        JPanel s = new JPanel(new GridBagLayout());
        s.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        JLabel c = new JLabel(caption);
        c.putClientProperty(FlatClientProperties.STYLE, "font: -2 bold; foreground: $Label.disabledForeground");
        g.gridy = 0; g.insets = new Insets(0, 2, 6, 0); s.add(c, g);
        for (int i = 0; i < rows.length; i++) { g.gridy = i + 1; g.insets = new Insets(0, 0, 6, 0); s.add(rows[i], g); }
        return s;
    }

    private static JPanel row(JComponent... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        r.setOpaque(false);
        r.setBorder(new EmptyBorder(0, -6, 0, 0));
        for (JComponent c : cs) { c.setOpaque(c instanceof JCheckBox ? false : c.isOpaque()); r.add(c); }
        return r;
    }

    private static JLabel muted(String t) {
        JLabel l = new JLabel(t);
        l.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return l;
    }

    private void installDrop(JComponent c) {
        c.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return !busy && s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) return false;
                    scan(files.get(0).toPath());
                    return true;
                } catch (Exception e) { return false; }
            }
        });
    }

    // ---------------------------------------------------------------------------------------- theme

    private void switchTheme(Theme.Mode m) {
        themeMode = m;
        prefs.put("theme", m.name());
        reapplyTheme();
    }

    private void reapplyTheme() {
        Theme.apply(themeMode);
        FlatLaf.updateUI();
        restyle();
    }

    /** Colors set directly (not via UIManager keys) must be refreshed after a theme switch. */
    private void restyle() {
        Color alt = Theme.panelAlt();
        sessionList.setBackground(alt);
        sidebarScroll.setBorder(null);
        sidebarScroll.getViewport().setBackground(alt);
        for (Component c : getContentPane().getComponents()) restyleBorders(c);
        alignAllHeaders();
        repaint();
    }

    private void restyleBorders(Component c) {
        if (c instanceof JComponent j) {
            if (j.getBorder() instanceof javax.swing.border.CompoundBorder cb && cb.getOutsideBorder() instanceof MatteBorder mb) {
                Insets i = mb.getBorderInsets(j);
                j.setBorder(BorderFactory.createCompoundBorder(new MatteBorder(i.top, i.left, i.bottom, i.right, Theme.border()), cb.getInsideBorder()));
                if (i.top > 0) j.setBackground(Theme.panelAlt()); // export bar
            }
            if (j instanceof JSplitPane sp && sp.getLeftComponent() instanceof JPanel side) side.setBackground(Theme.panelAlt());
            for (Component k : j.getComponents()) restyleBorders(k);
        }
    }

    private void recheckSystemTheme() {
        if (themeMode != Theme.Mode.SYSTEM) return;
        Thread t = new Thread(() -> {
            boolean d = Theme.systemIsDark();
            if (d != Theme.isDark()) SwingUtilities.invokeLater(this::reapplyTheme);
        }, "theme-check");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------------------------------- prefs

    private void loadPrefs() {
        outDir.setText(prefs.get("outDir", Cli.defaultOut().toString()));
        format.setSelectedIndex(Math.min(prefs.getInt("format", 0), format.getItemCount() - 1));
        dither.setSelected(prefs.getBoolean("dither", true));
        normalize.setSelectedIndex(Math.min(prefs.getInt("normalize", 0), normalize.getItemCount() - 1));
        normDb.setValue(prefs.getDouble("normDb", -1.0));
        removeSilent.setSelected(prefs.getBoolean("removeSilent", false));
        silentDb.setValue(prefs.getDouble("silentDb", -60.0));
        reaper.setSelected(prefs.getBoolean("reaper", true));
        markerFiles.setSelected(prefs.getBoolean("markers", true));
    }

    private void savePrefs() {
        prefs.put("outDir", outDir.getText());
        prefs.putInt("format", format.getSelectedIndex());
        prefs.putBoolean("dither", dither.isSelected());
        prefs.putInt("normalize", normalize.getSelectedIndex());
        prefs.putDouble("normDb", ((Number) normDb.getValue()).doubleValue());
        prefs.putBoolean("removeSilent", removeSilent.isSelected());
        prefs.putDouble("silentDb", ((Number) silentDb.getValue()).doubleValue());
        prefs.putBoolean("reaper", reaper.isSelected());
        prefs.putBoolean("markers", markerFiles.isSelected());
    }

    // ---------------------------------------------------------------------------------------- sources

    private void chooseSource() {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", null));
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setDialogTitle("Choose the SD card, an X_LIVE / session folder, or a multichannel WAV");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        prefs.put("lastDir", (Files.isDirectory(p) ? p : p.getParent()).toString());
        scan(p);
    }

    private void rescan() { if (lastSource != null) scan(lastSource); }

    private void scan(Path p) {
        runTask("Scanning", pr -> {
            List<String> warn = new ArrayList<>();
            List<Session> found = Session.scan(p, warn);
            SwingUtilities.invokeLater(() -> {
                showSessions(p, found);
                if (!found.isEmpty()) sessionList.setSelectedIndex(0);
                status.setText(found.isEmpty() ? "No recordings found in " + p : found.size() + " recording(s) found" + (warn.isEmpty() ? "" : " · " + warn.size() + " warning(s)"));
                if (!warn.isEmpty()) JOptionPane.showMessageDialog(this, String.join("\n", warn.subList(0, Math.min(15, warn.size()))),
                        "Scan warnings", JOptionPane.WARNING_MESSAGE);
            });
        });
    }

    private void showSessions(Path p, List<Session> found) {
        lastSource = p;
        sourceLabel.setText(Util.shortPath(p) + (found.isEmpty() ? "" : "   ·   " + found.size() + (found.size() == 1 ? " recording" : " recordings")));
        sourceLabel.setToolTipText(p.toString());
        sessions.clear();
        selection.clear();
        for (Session s : found) sessions.addElement(s);
        countLabel.setText(found.isEmpty() ? "" : String.valueOf(found.size()));
        sidebarTip.setVisible(found.size() >= 2);
        showSession();
    }

    private Session current() { return sessionList.getSelectedValue(); }

    private boolean[][] sel(Session s) {
        return selection.computeIfAbsent(s, k -> {
            boolean[][] v = new boolean[2][k.channels];
            Arrays.fill(v[0], true);
            return v;
        });
    }

    private void showSession() {
        Session s = current();
        if (chTable.isEditing()) chTable.getCellEditor().stopCellEditing();
        chModel.set(s);
        mkModel.set(s);
        takeModel.set(s);
        mainCards.show(main, s == null ? "empty" : "session");
        if (exportBar != null) exportBar.setVisible(!sessions.isEmpty());
        if (s != null) {
            title.setText(s.name == null || s.name.isBlank() ? (s.date != null ? "Recording " + s.id : s.id) : s.name);
            String when = s.date == null ? "" : s.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy · HH:mm", Locale.ENGLISH));
            String kind = !s.parts.isEmpty() ? "Combined recording" : s.isCardSession() ? "Card session " + Util.shortPath(s.dir) : "WAV file " + s.id;
            subtitle.setText((when.isEmpty() ? "" : when + "   ·   ") + kind);
            subtitle.setToolTipText(String.valueOf(s.dir));
            chips.removeAll();
            chips.add(chip(s.channels + " channels"));
            chips.add(chip(Session.khz(s.sampleRate)));
            chips.add(chip(s.formatLabel));
            chips.add(chip(Util.hms(s.seconds())));
            chips.add(chip(Util.bytes(s.totalBytes())));
            int nt = s.takes.size() + s.parts.stream().mapToInt(x -> x.takes.size()).sum();
            chips.add(chip(nt + (nt == 1 ? " file" : " files")));
            chips.add(chip(s.markers.size() + (s.markers.size() == 1 ? " marker" : " markers")));
            if (!s.parts.isEmpty()) chips.add(chip("combined", Theme.WARN));
            if (s.anyBrokenHeaders()) chips.add(chip("unfinalized", Theme.WARN));
            chips.revalidate();
            chips.repaint();
            sessionName.setText(s.name);
            sessionName.setEnabled(s.isCardSession());
            rangeEnd.setText(Util.hms(s.seconds()));
            fillDetails(s);
            setTitle(APP + " — " + s.label());
        } else setTitle(APP);
        updateSummary();
        updateButtons();
    }

    private JComponent chip(String text) { return chip(text, null); }

    private JComponent chip(String text, Color tint) {
        JLabel l = new JLabel(text) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fg = tint != null ? tint : UIManager.getColor("Label.foreground");
                g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), Theme.isDark() ? 34 : 22));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
                super.paintComponent(g0);
            }
        };
        l.putClientProperty(FlatClientProperties.STYLE, "font: -1" + (tint != null ? "; foreground: #" + Integer.toHexString(tint.getRGB() & 0xFFFFFF) : ""));
        l.setBorder(new EmptyBorder(3, 10, 3, 10));
        return l;
    }

    private void fillDetails(Session s) {
        details.removeAll();
        String[][] rows = {
                {"Recording", s.label()},
                {"Location", String.valueOf(s.dir)},
                {"Type", !s.parts.isEmpty() ? "Combined (" + s.parts.size() + " recordings side by side)"
                        : s.isCardSession() ? "X-Live / WING-LIVE card session" : "Multichannel WAV file"},
                {"Audio", s.channels + " channels · " + s.sampleRate + " Hz · " + s.formatLabel},
                {"Length", Util.hmsMillis(s.seconds()) + "   (" + String.format(Locale.ROOT, "%,d", s.frames) + " samples)"},
                {"Size", Util.bytes(s.totalBytes())},
                {"Session id", s.log == null ? "—" : String.format("%08X", s.log.sessionId)},
        };
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < rows.length; i++) {
            g.gridy = i;
            g.gridx = 0; g.insets = new Insets(3, 0, 3, 24); g.weightx = 0;
            details.add(muted(rows[i][0]), g);
            g.gridx = 1; g.weightx = 1; g.insets = new Insets(3, 0, 3, 0);
            JLabel v = new JLabel(rows[i][1]);
            details.add(v, g);
        }
        details.revalidate();
        details.repaint();
    }

    private void updateSummary() {
        Session s = current();
        if (s == null) { chSummary.setText(" "); return; }
        boolean[][] v = sel(s);
        int ticked = 0, pairs = 0, active = 0;
        for (int c = 0; c < s.channels; c++) {
            if (v[0][c]) ticked++;
            if (v[1][c]) pairs++;
            if (s.peaks != null && Util.db(s.peaks[c]) >= silentThreshold()) active++;
        }
        String lv = s.peaks == null ? "Levels not analyzed yet. Click Analyze levels to see which inputs were used."
                : active + " of " + s.channels + " channels have signal above " + (int) silentThreshold() + " dBFS.";
        chSummary.setText(ticked + " of " + s.channels + " channels ticked" + (pairs > 0 ? " · " + pairs + " stereo pair(s)" : "") + "   ·   " + lv);
    }

    private double silentThreshold() { return ((Number) silentDb.getValue()).doubleValue(); }

    // ---------------------------------------------------------------------------------------- channel actions

    private void setAll(boolean v) {
        Session s = current();
        if (s == null) return;
        Arrays.fill(sel(s)[0], v);
        chModel.fireTableDataChanged();
        updateSummary();
    }

    private void analyze() {
        Session s = current();
        if (s == null) return;
        runTask("Analyzing", pr -> {
            s.peaks = ExportJob.analyzeSession(s, pr);
            SwingUtilities.invokeLater(() -> { chModel.fireTableDataChanged(); updateSummary(); status.setText("Levels analyzed"); });
        });
    }

    private void selectNonSilent() {
        Session s = current();
        if (s == null) return;
        if (s.peaks == null) { analyzeThen(this::selectNonSilent); return; }
        double thr = silentThreshold();
        boolean[] e = sel(s)[0];
        int on = 0;
        for (int c = 0; c < s.channels; c++) { e[c] = Util.db(s.peaks[c]) >= thr; if (e[c]) on++; }
        chModel.fireTableDataChanged();
        updateSummary();
        status.setText(on + " of " + s.channels + " channels have signal above " + (int) thr + " dBFS");
    }

    private void analyzeThen(Runnable after) {
        Session s = current();
        runTask("Analyzing", pr -> {
            s.peaks = ExportJob.analyzeSession(s, pr);
            SwingUtilities.invokeLater(() -> { chModel.fireTableDataChanged(); after.run(); });
        });
    }

    private void importNames() {
        Session s = current();
        if (s == null) return;
        JFileChooser fc = new JFileChooser(prefs.get("namesDir", null));
        fc.setDialogTitle("X32/M32 scene (.scn), WING snapshot (.snap) or text file with one name per line");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        prefs.put("namesDir", p.getParent().toString());
        try {
            List<String> names = NameImport.read(p);
            String offs = s.channels > 32 || names.size() <= s.channels ? "1" : JOptionPane.showInputDialog(this,
                    "The file has " + names.size() + " names. Which input feeds card channel 1?", "1");
            int off = offs == null ? 0 : Math.max(0, Integer.parseInt(offs.trim()) - 1);
            int n = 0;
            for (int c = 0; c < s.channels && c + off < names.size(); c++) {
                String nm = names.get(c + off);
                if (nm != null && !nm.isBlank()) { s.channelNames[c] = nm; n++; }
            }
            rememberNames(s);
            chModel.fireTableDataChanged();
            status.setText(n + " channel names imported from " + p.getFileName());
        } catch (Exception e) {
            error("Could not read names", e);
        }
    }

    private void saveNames() {
        Session s = current();
        if (s == null) return;
        JFileChooser fc = new JFileChooser(prefs.get("namesDir", null));
        fc.setSelectedFile(new File("channel names.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(fc.getSelectedFile().toPath(), String.join(System.lineSeparator(), s.channelNames) + System.lineSeparator());
        } catch (IOException e) { error("Could not save", e); }
    }

    private void rememberNames(Session s) { prefs.put("lastNames", String.join("\n", s.channelNames)); }

    private void recallNames() {
        Session s = current();
        String v = prefs.get("lastNames", null);
        if (s == null || v == null) { status.setText("No names remembered yet"); return; }
        String[] n = v.split("\n", -1);
        for (int c = 0; c < Math.min(n.length, s.channels); c++) if (!n[c].isBlank()) s.channelNames[c] = n[c];
        chModel.fireTableDataChanged();
    }

    // ---------------------------------------------------------------------------------------- markers

    private void addMarker() {
        Session s = current();
        if (s == null) return;
        String t = JOptionPane.showInputDialog(this, "Marker time (h:mm:ss.sss, mm:ss or seconds):", "0:00");
        if (t == null) return;
        try {
            long m = Math.round(Util.parseTime(t) * s.sampleRate);
            if (m <= 0 || m >= s.frames) throw new IllegalArgumentException("Time is outside the recording");
            s.markers.add(m);
            s.markers.sort(null);
            mkModel.fireTableDataChanged();
            showSession();
        } catch (RuntimeException e) { error("Invalid time", e); }
    }

    private void removeMarkers() {
        Session s = current();
        if (s == null) return;
        int[] rows = mkTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) s.markers.remove(mkTable.convertRowIndexToModel(rows[i]));
        mkModel.fireTableDataChanged();
        showSession();
    }

    private void saveLog() {
        Session s = current();
        if (s == null || !s.isCardSession()) { status.setText("Only card sessions have a SE_LOG.BIN"); return; }
        String n = sessionName.getText().trim();
        if (n.length() > 15) n = n.substring(0, 15);
        if (JOptionPane.showConfirmDialog(this, "Write name \"" + n + "\" and " + s.markers.size() + " marker(s) to\n" + s.logPath
                + "\n\nThe original is kept as SE_LOG.BIN.bak.", "Save to card", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        s.name = n;
        try {
            s.saveLog();
            sessionList.repaint();
            showSession();
            status.setText("Saved " + s.logPath);
        } catch (IOException e) { error("Could not write SE_LOG.BIN", e); }
    }

    private void exportMarkers() {
        Session s = current();
        if (s == null || s.markers.isEmpty()) { status.setText("No markers to export"); return; }
        JFileChooser fc = new JFileChooser(outDir.getText());
        fc.setSelectedFile(new File(Util.safeFileName(s.label()) + " markers.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        try {
            if (p.toString().toLowerCase().endsWith(".txt")) Exports.writeAudacityLabels(p, s.markers, s.sampleRate);
            else Exports.writeMarkersCsv(p, s.markers, s.sampleRate);
            status.setText("Markers written to " + p.getFileName());
        } catch (IOException e) { error("Could not write markers", e); }
    }

    // ---------------------------------------------------------------------------------------- export

    private void chooseOut() {
        JFileChooser fc = new JFileChooser(outDir.getText());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) outDir.setText(fc.getSelectedFile().getPath());
    }

    private List<ExportJob.Track> tracksFor(Session s, boolean all) {
        boolean[][] v = sel(s);
        List<ExportJob.Track> t = new ArrayList<>();
        for (int c = 0; c < s.channels; c++) {
            if (all) { t.add(new ExportJob.Track(s.channelNames[c], new int[] {c})); continue; }
            if (!v[0][c]) continue;
            if (v[1][c] && c + 1 < s.channels) {
                String a = s.channelNames[c], b = s.channelNames[c + 1];
                t.add(new ExportJob.Track(a.equals(b) ? a : a + " + " + b, new int[] {c, c + 1}));
                c++;
            } else t.add(new ExportJob.Track(s.channelNames[c], new int[] {c}));
        }
        return t;
    }

    private void export(boolean quick) {
        if (chTable.isEditing()) chTable.getCellEditor().stopCellEditing();
        List<Session> chosen = sessionList.getSelectedValuesList();
        if (chosen.isEmpty()) { status.setText("Select a recording first"); return; }
        Path root = Path.of(outDir.getText().trim());
        List<ExportJob.Spec> specs = new ArrayList<>();
        try {
            for (Session s : chosen) {
                ExportJob.Spec sp = new ExportJob.Spec();
                sp.session = s;
                sp.tracks = tracksFor(s, quick);
                sp.format = quick ? ExportJob.Format.FLAC16 : (ExportJob.Format) format.getSelectedItem();
                sp.dither = quick || dither.isSelected();
                sp.normalize = (ExportJob.Normalize) normalize.getSelectedItem();
                sp.normalizeDb = ((Number) normDb.getValue()).doubleValue();
                sp.removeSilent = removeSilent.isSelected();
                sp.silentDb = silentThreshold();
                sp.range = quick ? ExportJob.Range.WHOLE : switch (range.getSelectedIndex()) {
                    case 1 -> ExportJob.Range.SPLIT_AT_MARKERS; case 2 -> ExportJob.Range.CUSTOM; default -> ExportJob.Range.WHOLE; };
                if (sp.range == ExportJob.Range.CUSTOM) {
                    sp.customStart = Math.round(Util.parseTime(rangeStart.getText()) * s.sampleRate);
                    sp.customEnd = Math.round(Util.parseTime(rangeEnd.getText()) * s.sampleRate);
                    if (sp.customEnd <= sp.customStart) throw new IllegalArgumentException("The range end must be after its start.");
                }
                sp.reaperProject = reaper.isSelected();
                sp.markerFiles = markerFiles.isSelected();
                sp.outDir = root.resolve(Util.safeFileName(s.label()));
                if (sp.tracks.isEmpty()) throw new IllegalArgumentException(s.label() + ": no channels are ticked.");
                if (Files.isDirectory(sp.outDir) && !isEmpty(sp.outDir)
                        && JOptionPane.showConfirmDialog(this, sp.outDir + "\nalready exists. Files with the same names will be overwritten.",
                        "Overwrite?", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                specs.add(sp);
                rememberNames(s);
            }
        } catch (RuntimeException e) { error("Cannot export", e); return; }
        savePrefs();
        runTask("Exporting", pr -> {
            List<ExportJob.Result> results = new ArrayList<>();
            for (ExportJob.Spec sp : specs) results.add(new ExportJob(sp, pr).run());
            SwingUtilities.invokeLater(() -> exportDone(specs, results));
        });
    }

    private static boolean isEmpty(Path d) {
        try (var st = Files.list(d)) { return st.findAny().isEmpty(); } catch (IOException e) { return true; }
    }

    private void exportDone(List<ExportJob.Spec> specs, List<ExportJob.Result> results) {
        chModel.fireTableDataChanged();
        updateSummary();
        StringBuilder b = new StringBuilder();
        long in = 0, out = 0; double sec = 0, audio = 0; int files = 0, skipped = 0;
        for (int i = 0; i < results.size(); i++) {
            ExportJob.Result r = results.get(i);
            in += r.bytesIn; out += r.bytesOut; sec += r.seconds; files += r.files.size(); skipped += r.skippedSilent.size();
            for (ExportJob.Region rg : new ExportJob(specs.get(i), null).regions()) audio += rg.length() / (double) specs.get(i).session.sampleRate;
        }
        b.append(String.format(Locale.ROOT, "%d file(s) written, %s (source %s) in %.1f s — %.0fx realtime.%n", files, Util.bytes(out), Util.bytes(in), sec,
                audio / Math.max(0.01, sec)));
        if (skipped > 0) b.append(skipped).append(" silent track(s) skipped:\n");
        for (ExportJob.Result r : results) for (String s : r.skippedSilent.subList(0, Math.min(40, r.skippedSilent.size()))) b.append("   ").append(s).append('\n');
        status.setText(files + " files written to " + specs.get(0).outDir);
        Path dir = specs.get(0).outDir;
        Object[] opts = {"Open folder", "Close"};
        if (JOptionPane.showOptionDialog(this, b.toString(), "Export finished", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, opts, opts[0]) == 0) openFolder(dir);
    }

    private void openFolder(Path p) {
        try {
            Files.createDirectories(p);
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception e) { error("Could not open folder", e); }
    }

    private void browse(String url) {
        try { Desktop.getDesktop().browse(java.net.URI.create(url)); } catch (Exception e) { error("Could not open browser", e); }
    }

    private void about() {
        JPanel p = new JPanel(new BorderLayout(18, 0));
        java.net.URL icon = Gui.class.getResource("icon.png");
        if (icon != null) p.add(new JLabel(new ImageIcon(new ImageIcon(icon).getImage().getScaledInstance(72, 72, Image.SCALE_SMOOTH))), BorderLayout.WEST);
        JLabel t = new JLabel("<html><b style='font-size:13px'>" + APP + "</b><br>Version " + Main.VERSION
                + "<br><br>Multitrack tool for Behringer X-Live and WING-LIVE recordings.<br>MIT licensed · github.com/Audbol/x32-wing-multitrack-tool</html>");
        p.add(t, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, p, "About", JOptionPane.PLAIN_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------- tools

    private void combineSelected() {
        List<Session> chosen = sessionList.getSelectedValuesList();
        try {
            Session c = Session.combine(chosen);
            sessions.addElement(c);
            countLabel.setText(String.valueOf(sessions.size()));
            sessionList.setSelectedValue(c, true);
            status.setText("Combined " + chosen.size() + " recordings into " + c.channels + " channels");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage() + "\n\nCtrl-click two recordings (e.g. WING card A and card B of the same show) first.",
                    "Combine", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void repairHeaders() {
        List<WavFile> broken = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) for (WavFile w : sessions.get(i).takes) if (w.headerBroken) broken.add(w);
        if (broken.isEmpty()) { JOptionPane.showMessageDialog(this, "No unfinalized recordings found in the scanned location."); return; }
        StringBuilder b = new StringBuilder("These files were not closed properly (card removed or power lost while recording).\n"
                + "This tool reads them fine; repairing rewrites 8 header bytes in place so other software can open them too.\n\n");
        for (WavFile w : broken) b.append(w.path).append('\n');
        if (JOptionPane.showConfirmDialog(this, b.toString(), "Repair headers", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        runTask("Repairing", pr -> {
            for (WavFile w : broken) w.repairHeader();
            SwingUtilities.invokeLater(this::rescan);
        });
    }

    private void selfTest() {
        runTask("Self-test", pr -> {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.io.PrintStream old = System.out;
            boolean ok;
            synchronized (System.class) {
                System.setOut(new java.io.PrintStream(buf, true));
                try { ok = SelfTest.run(); } finally { System.setOut(old); }
            }
            String text = buf.toString();
            boolean fine = ok;
            SwingUtilities.invokeLater(() -> {
                JTextArea a = new JTextArea(text, 24, 90);
                a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                JOptionPane.showMessageDialog(this, new JScrollPane(a), fine ? "Self-test passed" : "Self-test FAILED",
                        fine ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
            });
        });
    }

    private void buildDialog() {
        JDialog d = new JDialog(this, "Build card session (virtual soundcheck)", true);
        DefaultListModel<Path> files = new DefaultListModel<>();
        JList<Path> list = new JList<>(files);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                Path p = (Path) v;
                c.setText(String.format("%2d   %s", i + 1, p.getFileName()));
                c.setToolTipText(p.toString());
                c.setIcon(Icons.of(Icons.Kind.FILE));
                c.setBorder(new EmptyBorder(3, 8, 3, 8));
                return c;
            }
        });
        JTextField name = new JTextField(15), markers = new JTextField(28), out = new JTextField(prefs.get("buildOut", outDir.getText()), 28);
        name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "optional, up to 15 characters");
        markers.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. 3:20, 7:45.5");
        JButton add = new JButton("Add files…", Icons.of(Icons.Kind.PLUS)), remove = new JButton("Remove", Icons.of(Icons.Kind.MINUS));
        JButton up = new JButton("Move up"), down = new JButton("Move down"), sort = new JButton("Sort by name");
        JButton browse = new JButton(Icons.of(Icons.Kind.FOLDER)), build = new JButton("Build session"), close = new JButton("Cancel");
        add.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(prefs.get("buildIn", null));
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("WAV / FLAC", "wav", "flac"));
            if (fc.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
            for (File f : fc.getSelectedFiles()) files.addElement(f.toPath());
            prefs.put("buildIn", fc.getCurrentDirectory().getPath());
        });
        remove.addActionListener(e -> { int[] r = list.getSelectedIndices(); for (int i = r.length - 1; i >= 0; i--) files.remove(r[i]); });
        up.addActionListener(e -> { int i = list.getSelectedIndex(); if (i > 0) { Path p = files.remove(i); files.add(i - 1, p); list.setSelectedIndex(i - 1); } });
        down.addActionListener(e -> { int i = list.getSelectedIndex(); if (i >= 0 && i + 1 < files.size()) { Path p = files.remove(i); files.add(i + 1, p); list.setSelectedIndex(i + 1); } });
        sort.addActionListener(e -> {
            List<Path> l = new ArrayList<>(); for (int i = 0; i < files.size(); i++) l.add(files.get(i));
            l.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
            files.clear(); l.forEach(files::addElement);
        });
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(out.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Output: the SD card root, or any folder (an X_LIVE folder is created inside)");
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) out.setText(fc.getSelectedFile().getPath());
        });
        close.addActionListener(e -> d.dispose());
        build.addActionListener(e -> {
            if (files.isEmpty()) return;
            List<Path> in = new ArrayList<>(); for (int i = 0; i < files.size(); i++) in.add(files.get(i));
            List<Double> mk = new ArrayList<>();
            try {
                for (String m : markers.getText().split(",")) if (!m.isBlank()) mk.add(Util.parseTime(m));
            } catch (RuntimeException ex) { error("Invalid marker time", ex); return; }
            String nm = name.getText().trim();
            Path o = Path.of(out.getText().trim());
            prefs.put("buildOut", o.toString());
            d.dispose();
            runTask("Building", pr -> {
                Path dir = new XLiveBuilder().build(in, o, nm.length() > 15 ? nm.substring(0, 15) : nm, mk, pr);
                SwingUtilities.invokeLater(() -> {
                    status.setText("Card session written to " + dir);
                    Object[] opts = {"Open folder", "Close"};
                    if (JOptionPane.showOptionDialog(this, "Card session written to\n" + dir + "\n\nCopy the X_LIVE folder to the root of the SD card.",
                            "Build finished", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]) == 0) openFolder(dir);
                });
            });
        });

        JPanel fileButtons = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(0, 0, 6, 0);
        for (JButton b : List.of(add, remove, up, down, sort)) fileButtons.add(b, g);
        JLabel intro = new JLabel("<html>Add WAV or FLAC files in channel order: mono, stereo or multichannel, all at 44.1 kHz or all at 48 kHz, up to 32 channels.<br>"
                + "Channels are padded with silence to 8, 16 or 32, and the result plays from the card like a normal recording.</html>");
        intro.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.add(intro, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
        center.add(sp, BorderLayout.CENTER);
        center.add(fileButtons, BorderLayout.EAST);
        JPanel form = new JPanel(new GridBagLayout());
        g = new GridBagConstraints(); g.insets = new Insets(4, 0, 4, 10); g.anchor = GridBagConstraints.WEST;
        g.gridy = 0; form.add(muted("Session name"), g); form.add(name, g);
        g.gridy = 1; form.add(muted("Markers"), g); form.add(markers, g);
        g.gridy = 2; form.add(muted("Output folder"), g); form.add(out, g); form.add(browse, g);
        build.putClientProperty(FlatClientProperties.STYLE, "font: bold; margin: 6,16,6,16");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.add(close); btns.add(build);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new EmptyBorder(12, 0, 0, 0));
        south.add(form, BorderLayout.CENTER);
        south.add(btns, BorderLayout.SOUTH);
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(16, 18, 16, 18));
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(build);
        d.setSize(820, 600);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // ---------------------------------------------------------------------------------------- background tasks

    interface Task { void run(ExportJob.Progress p) throws Exception; }

    private void runTask(String what, Task t) {
        if (busy) return;
        busy = true;
        cancel = false;
        updateButtons();
        bar.setValue(0);
        bar.setIndeterminate(true);
        status.setText(what + "…");
        long start = System.nanoTime();
        ExportJob.Progress pr = new ExportJob.Progress() {
            long last;
            public void update(String stage, double f, String detail) {
                long now = System.nanoTime();
                if (now - last < 100_000_000L && f < 1) return;
                last = now;
                double el = (now - start) / 1e9;
                String eta = f > 0.01 && f < 1 ? " · " + Util.hms(el / f - el) + " left" : "";
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue((int) (f * 1000));
                    status.setText(String.format(Locale.ROOT, "%s · %.0f%%", stage, f * 100) + (detail.isEmpty() ? "" : " · " + detail) + eta);
                });
            }
            public boolean cancelled() { return cancel; }
        };
        Thread th = new Thread(() -> {
            try {
                t.run(pr);
            } catch (InterruptedIOException e) {
                SwingUtilities.invokeLater(() -> status.setText("Cancelled"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { status.setText(what + " failed"); error(what + " failed", e); });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    bar.setIndeterminate(false);
                    bar.setValue(0);
                    updateButtons();
                });
            }
        }, what);
        th.setDaemon(true);
        th.start();
    }

    private void updateButtons() {
        for (JComponent c : busyDisabled) c.setEnabled(!busy);
        cancelBtn.setVisible(busy);
        bar.setVisible(busy);
        boolean any = current() != null;
        exportBtn.setEnabled(!busy && any);
        quickBtn.setEnabled(!busy && any);
        boolean custom = range.getSelectedIndex() == 2;
        rangeStart.setEnabled(!busy && custom);
        rangeEnd.setEnabled(!busy && custom);
        normDb.setEnabled(!busy && normalize.getSelectedIndex() != 0);
        silentDb.setEnabled(!busy && removeSilent.isSelected());
        ExportJob.Format f = (ExportJob.Format) format.getSelectedItem();
        dither.setEnabled(!busy && f != null && f.bits < 32);
    }

    private void error(String title, Throwable e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        JOptionPane.showMessageDialog(this, m, title, JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------- models & renderers

    private final class ChannelModel extends AbstractTableModel {
        private Session s;
        private final String[] cols = {"Export", "#", "Name", "Stereo", "Peak level"};
        void set(Session s) { this.s = s; fireTableDataChanged(); }
        public int getRowCount() { return s == null ? 0 : s.channels; }
        public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 || c == 3 ? Boolean.class : c == 1 ? Integer.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return !busy && (c == 0 || c == 2 || c == 3); }
        public Object getValueAt(int r, int c) {
            return switch (c) {
                case 0 -> sel(s)[0][r];
                case 1 -> r + 1;
                case 2 -> s.channelNames[r];
                case 3 -> sel(s)[1][r];
                default -> s.peaks == null ? null : s.peaks[r];
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            switch (c) {
                case 0 -> sel(s)[0][r] = (Boolean) v;
                case 2 -> s.channelNames[r] = v.toString().trim().isEmpty() ? String.format("Ch %02d", r + 1) : v.toString().trim();
                case 3 -> {
                    boolean b = (Boolean) v && r + 1 < s.channels;
                    sel(s)[1][r] = b;
                    if (b) { sel(s)[1][r + 1] = false; if (r > 0) sel(s)[1][r - 1] = false; }
                    fireTableDataChanged();
                }
                default -> { }
            }
            updateSummary();
        }
    }

    private static final class MutedRenderer extends DefaultTableCellRenderer {
        MutedRenderer(int align) { setHorizontalAlignment(align); }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            if (!sel) setForeground(Theme.muted());
            return this;
        }
    }

    private final class NameRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            Session s = current();
            setIcon(null);
            if (s != null) {
                boolean[][] st = sel(s);
                if (st[1][r] && r + 1 < s.channels) { setIcon(Icons.of(Icons.Kind.LINK)); setToolTipText("Exported as one stereo file with channel " + (r + 2)); }
                else if (r > 0 && st[1][r - 1]) { setIcon(Icons.of(Icons.Kind.LINK)); setToolTipText("Right side of stereo pair " + r + "-" + (r + 1)); }
                else setToolTipText(null);
                if (!sel && !st[0][r]) setForeground(Theme.muted());
            }
            setIconTextGap(6);
            return this;
        }
    }

    /** Peak meter: bar scaled -60..0 dBFS, colored by level, greyed below the silence threshold. */
    private final class MeterRenderer extends JComponent implements TableCellRenderer {
        private Long peak;
        private boolean selected;
        private Color bg, fg;

        MeterRenderer() { setOpaque(false); }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            peak = (Long) v;
            selected = sel;
            fg = sel ? t.getSelectionForeground() : t.getForeground();
            bg = null;
            return this;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int h = getHeight(), textW = 70, barW = Math.max(40, getWidth() - textW - 18), bh = 6, y = (h - bh) / 2;
            Color track = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 28);
            g.setColor(track);
            g.fillRoundRect(8, y, barW, bh, bh, bh);
            String label;
            Color lc = selected ? fg : Theme.muted();
            if (peak == null) {
                label = "—";
            } else {
                double db = Util.db(peak);
                boolean silent = db < silentThreshold();
                label = Double.isInfinite(db) ? "silent" : String.format(Locale.ROOT, "%.1f dB", db);
                double frac = Double.isInfinite(db) ? 0 : Math.max(0, Math.min(1, (db + 60) / 60.0));
                Color col = silent ? Theme.muted() : db > -1 ? Theme.HOT : db > -6 ? Theme.WARN : Theme.GOOD;
                g.setColor(col);
                int w = (int) Math.round(barW * frac);
                if (w > 0) g.fillRoundRect(8, y, Math.max(bh, w), bh, bh, bh);
                if (!silent && !selected) lc = fg;
            }
            g.setFont(getFont() != null ? getFont() : UIManager.getFont("Table.font"));
            g.setFont(UIManager.getFont("Table.font").deriveFont(UIManager.getFont("Table.font").getSize2D() - 1f));
            g.setColor(lc);
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(label, getWidth() - 10 - fm.stringWidth(label), (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    private final class MarkerModel extends AbstractTableModel {
        private Session s;
        private final String[] cols = {"#", "Time", "Sample position"};
        void set(Session s) { this.s = s; fireTableDataChanged(); }
        public int getRowCount() { return s == null ? 0 : s.markers.size(); }
        public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            long m = s.markers.get(r);
            return switch (c) { case 0 -> r + 1; case 1 -> Util.hmsMillis(m / (double) s.sampleRate); default -> String.format(Locale.ROOT, "%,d", m); };
        }
    }

    private static final class TakeModel extends AbstractTableModel {
        private final List<WavFile> rows = new ArrayList<>();
        private final String[] cols = {"File", "Size", "Length", "Status"};
        void set(Session s) {
            rows.clear();
            if (s != null) { rows.addAll(s.takes); for (Session p : s.parts) rows.addAll(p.takes); }
            fireTableDataChanged();
        }
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            WavFile w = rows.get(r);
            return switch (c) {
                case 0 -> w.path.getParent().getFileName() + File.separator + w.path.getFileName();
                case 1 -> Util.bytes(w.fileSize);
                case 2 -> Util.hms(w.frames() / (double) w.sampleRate);
                default -> w.headerBroken ? "Unfinalized (readable; Tools › Repair fixes it for other software)" : "OK";
            };
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            if (!sel) setForeground("OK".equals(v) ? Theme.GOOD : Theme.WARN);
            return this;
        }
    }

    private static final class SessionRenderer extends JPanel implements ListCellRenderer<Session> {
        private final JLabel name = new JLabel(), meta = new JLabel(), when = new JLabel(), badge = new JLabel();
        private Color tile;

        @Override protected void paintComponent(Graphics g0) {
            if (tile == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(tile);
            g.fillRoundRect(8, 2, getWidth() - 16, getHeight() - 4, 12, 12);
            g.dispose();
        }

        SessionRenderer() {
            super(new BorderLayout(0, 2));
            setBorder(new EmptyBorder(10, 20, 10, 20));
            name.putClientProperty(FlatClientProperties.STYLE, "font: +1 bold");
            meta.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            when.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            badge.putClientProperty(FlatClientProperties.STYLE, "font: -2 bold");
            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            top.add(name, BorderLayout.CENTER);
            top.add(badge, BorderLayout.EAST);
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            bottom.add(when, BorderLayout.NORTH);
            bottom.add(meta, BorderLayout.SOUTH);
            add(top, BorderLayout.NORTH);
            add(bottom, BorderLayout.CENTER);
        }

        public Component getListCellRendererComponent(JList<? extends Session> l, Session s, int i, boolean sel, boolean focus) {
            name.setText(s.name == null || s.name.isBlank() ? (s.date != null ? "Recording " + s.id : s.id) : s.name);
            when.setText(s.date == null ? String.valueOf(s.dir) : s.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", Locale.ENGLISH)));
            meta.setText(s.channels + " ch · " + Session.khz(s.sampleRate) + " · " + Util.hms(s.seconds()) + " · " + Util.bytes(s.totalBytes())
                    + (s.markers.isEmpty() ? "" : " · " + s.markers.size() + " markers"));
            badge.setText(s.anyBrokenHeaders() ? "UNFINALIZED" : !s.parts.isEmpty() ? "COMBINED" : "");
            Color fg = sel ? l.getSelectionForeground() : l.getForeground();
            Color mutedC = sel ? new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 200) : Theme.muted();
            name.setForeground(fg);
            when.setForeground(mutedC);
            meta.setForeground(mutedC);
            badge.setForeground(sel ? fg : Theme.WARN);
            tile = sel ? l.getSelectionBackground() : null;
            setOpaque(false);
            return this;
        }
    }
}
