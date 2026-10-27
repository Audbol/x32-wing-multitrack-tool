package x32wing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Swing front end. */
final class Gui extends JFrame {
    private static final String TITLE = "X32 / M32 / WING Multitrack Tool " + Main.VERSION;
    private final Preferences prefs = Preferences.userNodeForPackage(Gui.class);

    private final DefaultListModel<Session> sessions = new DefaultListModel<>();
    private final JList<Session> sessionList = new JList<>(sessions);
    private final JLabel sourceLabel = new JLabel("Open an SD card, an X_LIVE folder or a WAV file — or drop it on this window.");
    private final Map<Session, boolean[][]> selection = new HashMap<>(); // [0]=export, [1]=stereo with next

    private final ChannelModel chModel = new ChannelModel();
    private final JTable chTable = new JTable(chModel);
    private final MarkerModel mkModel = new MarkerModel();
    private final JTable mkTable = new JTable(mkModel);
    private final JTextField sessionName = new JTextField(16);
    private final JTextArea info = new JTextArea();

    private final JComboBox<ExportJob.Format> format = new JComboBox<>(ExportJob.Format.values());
    private final JCheckBox dither = new JCheckBox("TPDF dither", true);
    private final JComboBox<String> range = new JComboBox<>(new String[] {"Whole recording", "Split at markers (folder per song)", "Custom range"});
    private final JTextField rangeStart = new JTextField("0:00", 7), rangeEnd = new JTextField("", 7);
    private final JComboBox<ExportJob.Normalize> normalize = new JComboBox<>(ExportJob.Normalize.values());
    private final JSpinner normDb = new JSpinner(new SpinnerNumberModel(-1.0, -40.0, 0.0, 0.5));
    private final JCheckBox removeSilent = new JCheckBox("Remove silent channels below");
    private final JSpinner silentDb = new JSpinner(new SpinnerNumberModel(-60.0, -120.0, -10.0, 1.0));
    private final JTextField outDir = new JTextField(30);
    private final JCheckBox reaper = new JCheckBox("REAPER project", true);
    private final JCheckBox markerFiles = new JCheckBox("Marker lists", true);

    private final JButton exportBtn = new JButton("Export selected");
    private final JButton quickBtn = new JButton("Quick 16-bit FLAC");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    private final List<JComponent> busyDisabled = new ArrayList<>();
    private volatile boolean cancel;
    private boolean busy;

    static void launch() {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            new Gui().setVisible(true);
        });
    }

    private Gui() {
        super(TITLE);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        java.net.URL icon = Gui.class.getResource("icon.png");
        if (icon != null) setIconImage(new ImageIcon(icon).getImage());
        setJMenuBar(menu());

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(new EmptyBorder(8, 8, 4, 8));
        JButton open = new JButton("Open card / folder…");
        open.addActionListener(e -> chooseSource());
        JButton rescan = new JButton("Rescan");
        rescan.addActionListener(e -> rescan());
        JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tb.add(open); tb.add(rescan);
        top.add(tb, BorderLayout.WEST);
        sourceLabel.setForeground(Color.GRAY);
        top.add(sourceLabel, BorderLayout.CENTER);
        busyDisabled.add(open); busyDisabled.add(rescan);

        sessionList.setCellRenderer(new SessionRenderer());
        sessionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSession(); });
        JScrollPane left = new JScrollPane(sessionList);
        left.setPreferredSize(new Dimension(300, 400));
        left.setBorder(BorderFactory.createTitledBorder("Recordings"));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Channels", channelsTab());
        tabs.addTab("Markers", markersTab());
        info.setEditable(false);
        info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Info", new JScrollPane(info));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs);
        split.setResizeWeight(0.25);
        split.setBorder(new EmptyBorder(0, 8, 0, 8));

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(exportPanel(), BorderLayout.SOUTH);
        setContentPane(root);
        installDrop(root);
        loadPrefs();
        setSize(1180, 780);
        setLocationRelativeTo(null);
        updateButtons();
    }

    // ---------------------------------------------------------------------------------------- layout

    private JMenuBar menu() {
        JMenuBar mb = new JMenuBar();
        JMenu f = new JMenu("File");
        f.add(item("Open card / folder…", this::chooseSource));
        f.add(item("Rescan", this::rescan));
        f.addSeparator();
        f.add(item("Exit", () -> System.exit(0)));
        JMenu t = new JMenu("Tools");
        t.add(item("Build card session from WAV/FLAC files (virtual soundcheck)…", this::buildDialog));
        t.add(item("Combine selected recordings side by side (WING card A + B)", this::combineSelected));
        t.add(item("Repair unfinalized recordings…", this::repairHeaders));
        t.addSeparator();
        t.add(item("Run self-test", this::selfTest));
        JMenu h = new JMenu("Help");
        h.add(item("Command line usage", () -> {
            JTextArea a = new JTextArea(Cli.USAGE);
            a.setEditable(false);
            a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JOptionPane.showMessageDialog(this, new JScrollPane(a), "Command line", JOptionPane.PLAIN_MESSAGE);
        }));
        h.add(item("About", () -> JOptionPane.showMessageDialog(this, TITLE + "\nVersion " + Main.VERSION
                + "\n\nhttps://github.com/Audbol/x32-wing-multitrack-tool", "About", JOptionPane.INFORMATION_MESSAGE)));
        mb.add(f); mb.add(t); mb.add(h);
        return mb;
    }

    private static JMenuItem item(String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        return i;
    }

    private JComponent channelsTab() {
        chTable.setRowHeight(22);
        chTable.getColumnModel().getColumn(0).setMaxWidth(60);
        chTable.getColumnModel().getColumn(1).setMaxWidth(50);
        chTable.getColumnModel().getColumn(3).setMaxWidth(120);
        chTable.getColumnModel().getColumn(4).setMaxWidth(110);
        chTable.getColumnModel().getColumn(4).setCellRenderer(new LevelRenderer());
        chTable.setFillsViewportHeight(true);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(button("All", () -> setAll(true)));
        buttons.add(button("None", () -> setAll(false)));
        buttons.add(button("Analyze levels", this::analyze));
        buttons.add(button("Select non-silent", this::selectNonSilent));
        buttons.add(new JSeparator(SwingConstants.VERTICAL));
        buttons.add(button("Import names (.scn / .snap / .txt)…", this::importNames));
        buttons.add(button("Save names…", this::saveNames));
        buttons.add(button("Recall last names", this::recallNames));

        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(chTable), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JComponent markersTab() {
        mkTable.setRowHeight(22);
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        north.add(new JLabel("Session name (max 15 chars):"));
        north.add(sessionName);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(button("Add marker…", this::addMarker));
        buttons.add(button("Remove selected", this::removeMarkers));
        buttons.add(button("Save name + markers to card (SE_LOG.BIN)", this::saveLog));
        buttons.add(button("Export markers…", this::exportMarkers));
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(mkTable), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JButton button(String label, Runnable r) {
        JButton b = new JButton(label);
        b.addActionListener(e -> r.run());
        busyDisabled.add(b);
        return b;
    }

    private JComponent exportPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(4, 8, 8, 8), BorderFactory.createTitledBorder("Export")));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.anchor = GridBagConstraints.WEST;

        JPanel r1 = row(new JLabel("Format"), format, dither, gap(), new JLabel("Range"), range, new JLabel("from"), rangeStart, new JLabel("to"), rangeEnd);
        JPanel r2 = row(new JLabel("Normalize"), normalize, new JLabel("target peak"), normDb, new JLabel("dBFS"), gap(),
                removeSilent, silentDb, new JLabel("dBFS peak"));
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseOut());
        JButton openOut = new JButton("Open");
        openOut.addActionListener(e -> openFolder(Path.of(outDir.getText())));
        JPanel r3 = row(new JLabel("Output folder"), outDir, browse, openOut, gap(), reaper, markerFiles);

        exportBtn.addActionListener(e -> export(false));
        quickBtn.addActionListener(e -> export(true));
        quickBtn.setFont(quickBtn.getFont().deriveFont(Font.BOLD));
        quickBtn.setToolTipText("Every channel of the selected recording(s) to its own 16-bit FLAC (dithered). Normalize / remove-silent settings above apply.");
        exportBtn.setToolTipText("Export the ticked channels with the settings above.");
        cancelBtn.addActionListener(e -> cancel = true);
        cancelBtn.setEnabled(false);
        bar.setStringPainted(true);
        bar.setString("");
        bar.setPreferredSize(new Dimension(260, 22));
        JPanel r4 = row(quickBtn, exportBtn, bar, cancelBtn, status);

        normDb.setToolTipText("Peak level every file is normalized to");
        silentDb.setToolTipText("Channels whose loudest peak stays below this are treated as unused and not exported");
        removeSilent.setToolTipText(silentDb.getToolTipText());
        normalize.setToolTipText("Each file: every track peaks at the target. All files, same gain: the loudest track hits the target and the mix balance is kept.");
        range.addActionListener(e -> updateButtons());
        normalize.addActionListener(e -> updateButtons());
        removeSilent.addActionListener(e -> { updateButtons(); chTable.repaint(); });
        silentDb.addChangeListener(e -> chTable.repaint());
        format.addActionListener(e -> updateButtons());
        busyDisabled.addAll(List.of(format, dither, range, rangeStart, rangeEnd, normalize, normDb, removeSilent, silentDb, outDir, browse,
                reaper, markerFiles, exportBtn, quickBtn));

        g.gridx = 0; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        g.gridy = 0; p.add(r1, g);
        g.gridy = 1; p.add(r2, g);
        g.gridy = 2; p.add(r3, g);
        g.gridy = 3; p.add(r4, g);
        return p;
    }

    private static JPanel row(JComponent... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JComponent c : cs) r.add(c);
        return r;
    }

    private static JComponent gap() { return (JComponent) Box.createHorizontalStrut(16); }

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

    private Path lastSource;

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
        lastSource = p;
        sourceLabel.setText(p.toString());
        runTask("Scanning", pr -> {
            List<String> warn = new ArrayList<>();
            List<Session> found = Session.scan(p, warn);
            SwingUtilities.invokeLater(() -> {
                sessions.clear();
                selection.clear();
                for (Session s : found) sessions.addElement(s);
                if (!found.isEmpty()) sessionList.setSelectedIndex(0);
                else showSession();
                status.setText(found.size() + " recording(s) found" + (warn.isEmpty() ? "" : ", " + warn.size() + " warning(s)"));
                if (!warn.isEmpty()) JOptionPane.showMessageDialog(this, String.join("\n", warn.subList(0, Math.min(15, warn.size()))),
                        "Scan warnings", JOptionPane.WARNING_MESSAGE);
            });
        });
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
        sessionName.setText(s == null ? "" : s.name);
        sessionName.setEnabled(s != null && s.isCardSession());
        info.setText(s == null ? "" : describe(s));
        info.setCaretPosition(0);
        if (s != null) {
            rangeEnd.setText(Util.hms(s.seconds()));
            setTitle(TITLE + " — " + s.label());
        }
        updateButtons();
    }

    private static String describe(Session s) {
        StringBuilder b = new StringBuilder();
        b.append("Recording:    ").append(s.label()).append('\n');
        b.append("Location:     ").append(s.dir).append('\n');
        b.append("Type:         ").append(!s.parts.isEmpty() ? "Combined (" + s.parts.size() + " recordings side by side)"
                : s.isCardSession() ? "X-Live / WING-LIVE card session (SE_LOG.BIN)" : "Multichannel WAV").append('\n');
        b.append("Format:       ").append(s.channels).append(" channels, ").append(s.sampleRate).append(" Hz, ").append(s.formatLabel).append('\n');
        b.append("Length:       ").append(Util.hmsMillis(s.seconds())).append("  (").append(s.frames).append(" samples)\n");
        b.append("Size:         ").append(Util.bytes(s.totalBytes())).append('\n');
        b.append("Markers:      ").append(s.markers.size()).append('\n');
        if (s.log != null) {
            b.append("Session id:   ").append(String.format("%08X", s.log.sessionId)).append('\n');
            b.append("Log length:   ").append(s.log.totalFrames).append(" samples, ").append(s.log.numTakes).append(" take(s)\n");
        }
        b.append('\n');
        List<WavFile> takes = new ArrayList<>(s.takes);
        for (Session p : s.parts) takes.addAll(p.takes);
        for (WavFile w : takes)
            b.append(String.format(Locale.ROOT, "  %-40s %10s  %s%s%n", w.path.getFileName(), Util.bytes(w.fileSize), Util.hms(w.frames() / (double) w.sampleRate),
                    w.headerBroken ? "   UNFINALIZED HEADER (readable; Tools > Repair to fix for other software)" : ""));
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------- channel actions

    private void setAll(boolean v) {
        Session s = current();
        if (s == null) return;
        Arrays.fill(sel(s)[0], v);
        chModel.fireTableDataChanged();
    }

    private void analyze() {
        Session s = current();
        if (s == null) return;
        runTask("Analyzing", pr -> {
            s.peaks = ExportJob.analyzeSession(s, pr);
            SwingUtilities.invokeLater(() -> { chModel.fireTableDataChanged(); status.setText("Levels analyzed"); });
        });
    }

    private void selectNonSilent() {
        Session s = current();
        if (s == null) return;
        if (s.peaks == null) { analyzeThen(this::selectNonSilent); return; }
        double thr = ((Number) silentDb.getValue()).doubleValue();
        boolean[] e = sel(s)[0];
        int on = 0;
        for (int c = 0; c < s.channels; c++) { e[c] = Util.db(s.peaks[c]) >= thr; if (e[c]) on++; }
        chModel.fireTableDataChanged();
        status.setText(on + " of " + s.channels + " channels have signal above " + thr + " dBFS");
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
        } catch (RuntimeException e) { error("Invalid time", e); }
    }

    private void removeMarkers() {
        Session s = current();
        if (s == null) return;
        int[] rows = mkTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) s.markers.remove(mkTable.convertRowIndexToModel(rows[i]));
        mkModel.fireTableDataChanged();
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
            status.setText("Markers written to " + p.getFileName() + " (.txt = Audacity labels, otherwise CSV)");
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
                sp.silentDb = ((Number) silentDb.getValue()).doubleValue();
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
        status.setText(files + " files written");
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

    // ---------------------------------------------------------------------------------------- tools

    private void combineSelected() {
        List<Session> chosen = sessionList.getSelectedValuesList();
        try {
            Session c = Session.combine(chosen);
            sessions.addElement(c);
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
        JTextField name = new JTextField(15), markers = new JTextField(30), out = new JTextField(prefs.get("buildOut", outDir.getText()), 30);
        JButton add = new JButton("Add files…"), remove = new JButton("Remove"), up = new JButton("Up"), down = new JButton("Down"), sort = new JButton("Sort by name");
        JButton browse = new JButton("Browse…"), build = new JButton("Build");
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
        g.gridx = 0; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(2, 2, 2, 2);
        for (JButton b : List.of(add, remove, up, down, sort)) fileButtons.add(b, g);
        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(new JLabel("<html>Files in channel order (mono, stereo or multichannel WAV/FLAC, all 44.1 or all 48 kHz, up to 32 channels).<br>"
                + "Channels are padded with silence to 8, 16 or 32.</html>"), BorderLayout.NORTH);
        center.add(new JScrollPane(list), BorderLayout.CENTER);
        center.add(fileButtons, BorderLayout.EAST);
        JPanel south = new JPanel(new GridBagLayout());
        g = new GridBagConstraints(); g.insets = new Insets(3, 3, 3, 3); g.anchor = GridBagConstraints.WEST;
        g.gridy = 0; south.add(new JLabel("Session name"), g); south.add(name, g);
        g.gridy = 1; south.add(new JLabel("Markers (e.g. 3:20, 7:45.5)"), g); south.add(markers, g);
        g.gridy = 2; south.add(new JLabel("Output folder"), g); south.add(out, g); south.add(browse, g);
        g.gridy = 3; south.add(build, g);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setSize(720, 520);
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
        bar.setString(what + "…");
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
                    bar.setValue((int) (f * 1000));
                    bar.setString(String.format(Locale.ROOT, "%.0f%%", f * 100));
                    status.setText(stage + (detail.isEmpty() ? "" : " · " + detail) + eta);
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
                SwingUtilities.invokeLater(() -> error(what + " failed", e));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    bar.setValue(0);
                    bar.setString("");
                    updateButtons();
                });
            }
        }, what);
        th.setDaemon(true);
        th.start();
    }

    private void updateButtons() {
        for (JComponent c : busyDisabled) c.setEnabled(!busy);
        cancelBtn.setEnabled(busy);
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
        private final String[] cols = {"Export", "#", "Name", "Stereo w/ next", "Peak"};
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
                default -> s.peaks == null ? "" : Util.dbLabel(s.peaks[r]);
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
        }
    }

    private final class LevelRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            Session s = current();
            setHorizontalAlignment(RIGHT);
            if (!sel && s != null && s.peaks != null && row < s.peaks.length) {
                double d = Util.db(s.peaks[row]);
                c.setForeground(d < ((Number) silentDb.getValue()).doubleValue() ? Color.GRAY : d > -0.5 ? new Color(200, 0, 0) : new Color(0, 120, 0));
            } else if (!sel) c.setForeground(t.getForeground());
            return c;
        }
    }

    private final class MarkerModel extends AbstractTableModel {
        private Session s;
        private final String[] cols = {"#", "Time", "Sample"};
        void set(Session s) { this.s = s; fireTableDataChanged(); }
        public int getRowCount() { return s == null ? 0 : s.markers.size(); }
        public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            long m = s.markers.get(r);
            return switch (c) { case 0 -> r + 1; case 1 -> Util.hmsMillis(m / (double) s.sampleRate); default -> m; };
        }
    }

    private static final class SessionRenderer extends JPanel implements ListCellRenderer<Session> {
        private final JLabel title = new JLabel(), sub = new JLabel();
        SessionRenderer() {
            super(new BorderLayout());
            setBorder(new EmptyBorder(4, 6, 4, 6));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            sub.setFont(sub.getFont().deriveFont(11f));
            add(title, BorderLayout.NORTH);
            add(sub, BorderLayout.SOUTH);
        }
        public Component getListCellRendererComponent(JList<? extends Session> l, Session s, int i, boolean sel, boolean focus) {
            title.setText(s.label() + (s.anyBrokenHeaders() ? "  ⚠" : ""));
            sub.setText(s.summary() + (s.markers.isEmpty() ? "" : " · " + s.markers.size() + " markers"));
            setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            title.setForeground(sel ? l.getSelectionForeground() : l.getForeground());
            sub.setForeground(sel ? l.getSelectionForeground() : Color.GRAY);
            setOpaque(true);
            return this;
        }
    }
}
