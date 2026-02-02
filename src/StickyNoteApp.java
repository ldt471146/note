import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class StickyNoteApp {
    private enum Theme { SYSTEM, LIGHT, DARK }
    private enum Scope { ACTIVE, ARCHIVED, ALL }

    private final AppPaths paths = new AppPaths();
    private final AppConfig config = new AppConfig(paths.configFile);
    private final NoteStore store = new NoteStore(paths);
    private final MarkdownPreview markdownPreview = new MarkdownPreview();

    private final DefaultListModel<Note> listModel = new DefaultListModel<Note>();
    private final JList<Note> noteList = new JList<Note>(listModel);
    private final JTextArea editor = new JTextArea();
    private final JEditorPane previewPane = new JEditorPane();
    private final JTextField searchField = new JTextField();
    private final JComboBox<Scope> scopeBox = new JComboBox<Scope>(Scope.values());
    private final JComboBox<String> tagBox = new JComboBox<String>();
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final Timer autoSaveTimer;
    private final UndoManager undoManager = new UndoManager();

    private JFrame frame;
    private JSplitPane splitPane;
    private boolean suppressDocEvents = false;
    private boolean dirty = false;
    private String currentNoteId = null;
    private long lastSnapshotAt = 0L;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                try {
                    new StickyNoteApp().start();
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null, e.toString(), "StickyNoteApp Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    public StickyNoteApp() {
        autoSaveTimer = new Timer(750, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                saveIfDirty(false);
            }
        });
        autoSaveTimer.setRepeats(false);
    }

    private void start() throws IOException {
        config.load();
        Theme t = Theme.valueOf(config.getString("theme", Theme.SYSTEM.name()));
        applyTheme(t);

        store.ensureLoaded();

        frame = new JFrame("便签");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 560));

        initUi();
        reloadFiltersAndList();
        restoreWindowConfig();

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                onExit();
            }
        });
        frame.addWindowFocusListener(new WindowFocusListener() {
            @Override public void windowGainedFocus(WindowEvent e) {}
            @Override public void windowLostFocus(WindowEvent e) {
                saveIfDirty(false);
                saveWindowConfig();
            }
        });

        frame.setVisible(true);
        focusEditor();
    }

    private void initUi() {
        frame.setJMenuBar(createMenuBar());

        JPanel root = new JPanel(new BorderLayout());
        root.add(createToolbar(), BorderLayout.NORTH);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createLeftPanel(), createEditorPanel());
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(10);
        root.add(splitPane, BorderLayout.CENTER);
        root.add(createStatusBar(), BorderLayout.SOUTH);

        frame.setContentPane(root);

        noteList.setCellRenderer(new NoteListCellRenderer());
        noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        noteList.addListSelectionListener(new ListSelectionListener() {
            @Override public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                onNoteSelected(noteList.getSelectedValue());
            }
        });

        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        editor.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        editor.getDocument().addUndoableEditListener(undoManager);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onEditorChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onEditorChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onEditorChanged(); }
        });

        previewPane.setEditable(false);
        previewPane.setContentType("text/html");
        previewPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        searchField.putClientProperty("JTextField.placeholderText", "搜索标题/内容/标签…");
        searchField.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                reloadListOnly();
            }
        });

        scopeBox.setSelectedItem(Scope.ACTIVE);
        scopeBox.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                reloadFiltersAndList();
            }
        });

        tagBox.setEditable(false);
        tagBox.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                reloadListOnly();
            }
        });

        installKeyBindings();
    }

    private JPanel createLeftPanel() {
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel row1 = new JPanel(new BorderLayout(8, 8));
        row1.add(searchField, BorderLayout.CENTER);
        row1.add(scopeBox, BorderLayout.EAST);

        JPanel row2 = new JPanel(new BorderLayout(8, 8));
        row2.add(new JLabel("标签"), BorderLayout.WEST);
        row2.add(tagBox, BorderLayout.CENTER);

        top.add(row1, BorderLayout.NORTH);
        top.add(row2, BorderLayout.SOUTH);

        JScrollPane listScroll = new JScrollPane(noteList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel left = new JPanel(new BorderLayout());
        left.add(top, BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);
        left.setMinimumSize(new Dimension(260, 200));
        return left;
    }

    private JComponent createEditorPanel() {
        JTabbedPane tabs = new JTabbedPane();
        JScrollPane editScroll = new JScrollPane(editor);
        editScroll.setBorder(BorderFactory.createEmptyBorder());

        JScrollPane previewScroll = new JScrollPane(previewPane);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());

        tabs.addTab("编辑", editScroll);
        tabs.addTab("预览", previewScroll);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 1) {
                markdownPreview.renderTo(previewPane, editor.getText());
            }
        });
        return tabs;
    }

    private JToolBar createToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        tb.add(button("新建", e -> actionNew()));
        tb.add(button("置顶", e -> actionTogglePinned()));
        tb.add(button("归档", e -> actionToggleArchived()));
        tb.add(button("标签", e -> actionEditTags()));
        tb.addSeparator();
        tb.add(button("历史", e -> actionHistory()));
        tb.addSeparator();
        tb.add(button("导入", e -> actionImport()));
        tb.add(button("导出", e -> actionExport()));
        tb.addSeparator();
        tb.add(button("删除", e -> actionDelete()));

        return tb;
    }

    private static JButton button(String text, ActionListener l) {
        JButton b = new JButton(text);
        b.addActionListener(l);
        b.setFocusable(false);
        return b;
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        statusLeft.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusRight.setForeground(UIManager.getColor("Label.disabledForeground"));
        bar.add(statusLeft, BorderLayout.WEST);
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    private JMenuBar createMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu file = new JMenu("文件");
        file.add(item("新建", KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask()), e -> actionNew()));
        file.add(item("导入…", KeyStroke.getKeyStroke(KeyEvent.VK_I, menuMask()), e -> actionImport()));
        file.add(item("导出…", KeyStroke.getKeyStroke(KeyEvent.VK_E, menuMask()), e -> actionExport()));
        file.addSeparator();
        file.add(item("打开数据目录", null, e -> actionOpenDataDir()));
        file.addSeparator();
        file.add(item("退出", KeyStroke.getKeyStroke(KeyEvent.VK_Q, menuMask()), e -> onExit()));

        JMenu edit = new JMenu("编辑");
        edit.add(item("撤销", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask()), e -> actionUndo()));
        edit.add(item("重做", KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask()), e -> actionRedo()));
        edit.addSeparator();
        edit.add(item("复制 Markdown", null, e -> actionCopyMarkdown()));
        edit.add(item("编辑标签…", KeyStroke.getKeyStroke(KeyEvent.VK_T, menuMask()), e -> actionEditTags()));
        edit.add(item("历史版本…", KeyStroke.getKeyStroke(KeyEvent.VK_H, menuMask()), e -> actionHistory()));

        JMenu view = new JMenu("视图");
        final JCheckBoxMenuItem alwaysOnTop = new JCheckBoxMenuItem("置顶窗口");
        alwaysOnTop.setState(config.getBool("alwaysOnTopWindow", false));
        alwaysOnTop.addActionListener(e -> {
            frame.setAlwaysOnTop(alwaysOnTop.getState());
            config.setBool("alwaysOnTopWindow", alwaysOnTop.getState());
            config.save();
        });
        view.add(alwaysOnTop);
        view.addSeparator();
        view.add(item("系统主题", null, e -> actionTheme(Theme.SYSTEM)));
        view.add(item("亮色主题", null, e -> actionTheme(Theme.LIGHT)));
        view.add(item("暗色主题", null, e -> actionTheme(Theme.DARK)));
        view.addSeparator();
        view.add(item("聚焦搜索", KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask()), e -> searchField.requestFocusInWindow()));

        JMenu help = new JMenu("帮助");
        help.add(item("关于", null, e -> JOptionPane.showMessageDialog(frame,
                "便签小程序（Java Swing）\n多便签 + Markdown 预览 + 自动保存\n\n数据目录：\n" + paths.appDir,
                "关于",
                JOptionPane.INFORMATION_MESSAGE)));

        mb.add(file);
        mb.add(edit);
        mb.add(view);
        mb.add(help);
        return mb;
    }

    private static JMenuItem item(String text, KeyStroke ks, ActionListener l) {
        JMenuItem it = new JMenuItem(text);
        if (ks != null) it.setAccelerator(ks);
        it.addActionListener(l);
        return it;
    }

    private static int menuMask() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        } catch (Exception e) {
            return InputEvent.CTRL_MASK;
        }
    }

    private void installKeyBindings() {
        InputMap im = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = editor.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask()), "saveNow");
        am.put("saveNow", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { saveIfDirty(true); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask()), "togglePinned");
        am.put("togglePinned", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { actionTogglePinned(); }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, menuMask()), "toggleArchived");
        am.put("toggleArchived", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { actionToggleArchived(); }
        });
    }

    private void onEditorChanged() {
        if (suppressDocEvents) return;
        dirty = true;
        statusLeft.setText("未保存…");
        updateCounts();
        autoSaveTimer.restart();
    }

    private void updateCounts() {
        String text = editor.getText();
        int chars = text == null ? 0 : text.length();
        int words = countWords(text);
        statusRight.setText("字数 " + words + "  字符 " + chars);
    }

    private static int countWords(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.length() == 0) return 0;
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ws = Character.isWhitespace(c);
            if (!ws && !inWord) {
                count++;
                inWord = true;
            } else if (ws) {
                inWord = false;
            }
        }
        return count;
    }

    private void onNoteSelected(Note n) {
        if (n == null) return;
        if (n.id != null && n.id.equals(currentNoteId)) return;
        saveIfDirty(false);
        loadNoteIntoEditor(n);
        config.setString("lastNoteId", n.id);
        config.save();
    }

    private void loadNoteIntoEditor(Note n) {
        suppressDocEvents = true;
        try {
            undoManager.discardAllEdits();
            editor.setText(n == null ? "" : safe(n.content));
            editor.setCaretPosition(0);
            currentNoteId = n == null ? null : n.id;
            dirty = false;
            statusLeft.setText("已加载");
            updateCounts();
        } finally {
            suppressDocEvents = false;
        }
    }

    private void saveIfDirty(boolean forceSnapshot) {
        if (currentNoteId == null) return;
        if (!dirty && !forceSnapshot) return;

        Note n = store.getById(currentNoteId);
        if (n == null) return;

        n.content = editor.getText();

        boolean writeHistory = forceSnapshot;
        long now = System.currentTimeMillis();
        if (!writeHistory && now - lastSnapshotAt > 20_000L) writeHistory = true;

        try {
            store.updateNote(n, writeHistory);
            if (writeHistory) lastSnapshotAt = now;
            dirty = false;
            statusLeft.setText("已保存");
            reloadListOnlyPreserveSelection(currentNoteId);
        } catch (IOException e) {
            statusLeft.setText("保存失败：" + e.getMessage());
        }
    }

    private void reloadFiltersAndList() {
        Scope scope = (Scope) scopeBox.getSelectedItem();
        boolean includeArchived = scope == Scope.ALL;

        tagBox.removeAllItems();
        tagBox.addItem("（全部标签）");
        List<String> tags = new ArrayList<String>(store.collectTags(includeArchived));
        Collections.sort(tags);
        for (int i = 0; i < tags.size(); i++) tagBox.addItem(tags.get(i));

        reloadListOnly();
    }

    private void reloadListOnly() {
        reloadListOnlyPreserveSelection(currentNoteId);
    }

    private void reloadListOnlyPreserveSelection(String keepId) {
        listModel.clear();

        Scope scope = (Scope) scopeBox.getSelectedItem();
        String q = searchField.getText();
        String tag = selectedTag();

        List<Note> all = new ArrayList<Note>(store.getAll());
        Collections.sort(all, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                if (a.updatedAt == b.updatedAt) return 0;
                return a.updatedAt > b.updatedAt ? -1 : 1;
            }
        });

        for (int i = 0; i < all.size(); i++) {
            Note n = all.get(i);
            if (scope == Scope.ACTIVE && n.archived) continue;
            if (scope == Scope.ARCHIVED && !n.archived) continue;
            if (!n.matchesQuery(q)) continue;
            if (tag != null && tag.length() > 0 && !hasTag(n, tag)) continue;
            listModel.addElement(n);
        }

        selectByIdOrFirst(keepId);
    }

    private void selectByIdOrFirst(String id) {
        int idx = -1;
        if (id != null) {
            for (int i = 0; i < listModel.size(); i++) {
                Note n = listModel.get(i);
                if (id.equals(n.id)) { idx = i; break; }
            }
        }
        if (idx < 0 && listModel.size() > 0) idx = 0;
        if (idx >= 0) {
            noteList.setSelectedIndex(idx);
            noteList.ensureIndexIsVisible(idx);
        }
    }

    private String selectedTag() {
        Object o = tagBox.getSelectedItem();
        if (o == null) return null;
        String s = o.toString();
        if (s.contains("全部")) return null;
        return s;
    }

    private static boolean hasTag(Note n, String tag) {
        if (n == null || n.tags == null) return false;
        for (int i = 0; i < n.tags.size(); i++) {
            if (tag.equalsIgnoreCase(n.tags.get(i))) return true;
        }
        return false;
    }

    private void actionNew() {
        saveIfDirty(false);
        try {
            Note n = store.createNote();
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
            loadNoteIntoEditor(n);
            focusEditor();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "新建失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionDelete() {
        Note n = selectedNote();
        if (n == null) return;
        int ok = JOptionPane.showConfirmDialog(frame, "确定要删除这条便签吗？", "确认删除", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            store.deleteNote(n.id);
            currentNoteId = null;
            dirty = false;
            reloadFiltersAndList();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "删除失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionTogglePinned() {
        Note n = selectedNote();
        if (n == null) return;
        n.pinned = !n.pinned;
        try {
            store.updateNote(n, false);
            reloadListOnly();
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private void actionToggleArchived() {
        Note n = selectedNote();
        if (n == null) return;
        n.archived = !n.archived;
        try {
            store.updateNote(n, false);
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private void actionEditTags() {
        Note n = selectedNote();
        if (n == null) return;
        String before = n.tagsJoined();
        String input = (String) JOptionPane.showInputDialog(frame, "输入标签（逗号分隔）", "编辑标签",
                JOptionPane.PLAIN_MESSAGE, null, null, before);
        if (input == null) return;

        List<String> next = new ArrayList<String>();
        String[] parts = input.split("[,，]");
        for (int i = 0; i < parts.length; i++) {
            String t = NoteStore.normalizeTag(parts[i]);
            if (t.length() == 0) continue;
            if (!containsIgnoreCase(next, t)) next.add(t);
        }
        n.tags = next;
        try {
            store.updateNote(n, false);
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    private void actionUndo() {
        try {
            if (undoManager.canUndo()) undoManager.undo();
        } catch (CannotUndoException ignored) {}
    }

    private void actionRedo() {
        try {
            if (undoManager.canRedo()) undoManager.redo();
        } catch (CannotRedoException ignored) {}
    }

    private void actionTheme(Theme t) {
        applyTheme(t);
        config.setString("theme", t.name());
        config.save();
        SwingUtilities.updateComponentTreeUI(frame);
        restoreWindowConfig();
    }

    private void actionHistory() {
        Note n = selectedNote();
        if (n == null) return;
        saveIfDirty(false);
        List<Path> files = store.listHistoryFiles(n.id);
        if (files.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "还没有历史版本（保存后会写入快照）", "历史版本", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        DefaultListModel<Path> m = new DefaultListModel<Path>();
        for (int i = 0; i < files.size(); i++) m.addElement(files.get(i));
        JList<Path> list = new JList<Path>(m);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path) setText(((Path) value).getFileName().toString().replace(".txt", ""));
                return this;
            }
        });

        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(360, 240));
        int ok = JOptionPane.showConfirmDialog(frame, sp, "选择要回滚的版本（确定后覆盖当前内容）", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        Path chosen = list.getSelectedValue();
        if (chosen == null) return;

        try {
            String content = store.readHistoryFile(chosen);
            suppressDocEvents = true;
            try {
                editor.setText(content == null ? "" : content);
                editor.setCaretPosition(0);
                dirty = true;
                statusLeft.setText("已回滚（未保存）");
                updateCounts();
            } finally {
                suppressDocEvents = false;
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "读取历史失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionExport() {
        Note n = selectedNote();
        if (n == null) return;
        saveIfDirty(false);
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(n.title().replaceAll("[\\\\/:*?\"<>|]", "_") + ".md"));
        int ok = fc.showSaveDialog(frame);
        if (ok != JFileChooser.APPROVE_OPTION) return;
        Path file = fc.getSelectedFile().toPath();
        try {
            Files.write(file, safe(n.content).getBytes(StandardCharsets.UTF_8));
            statusLeft.setText("已导出：" + file.getFileName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "导出失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionImport() {
        JFileChooser fc = new JFileChooser();
        int ok = fc.showOpenDialog(frame);
        if (ok != JFileChooser.APPROVE_OPTION) return;
        Path file = fc.getSelectedFile().toPath();
        try {
            byte[] bytes = Files.readAllBytes(file);
            String content = new String(bytes, StandardCharsets.UTF_8);
            Note n = store.createNote();
            n.content = content;
            store.updateNote(n, true);
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
            loadNoteIntoEditor(n);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "导入失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionOpenDataDir() {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(paths.appDir.toFile());
        } catch (IOException ignored) {}
    }

    private void actionCopyMarkdown() {
        String text = editor.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text == null ? "" : text), null);
        statusLeft.setText("已复制到剪贴板");
    }

    private Note selectedNote() {
        Note n = noteList.getSelectedValue();
        if (n != null) return n;
        if (currentNoteId != null) return store.getById(currentNoteId);
        return null;
    }

    private void onExit() {
        saveIfDirty(false);
        saveWindowConfig();
        frame.dispose();
        System.exit(0);
    }

    private void restoreWindowConfig() {
        int w = config.getInt("w", 980);
        int h = config.getInt("h", 640);
        int x = config.getInt("x", -1);
        int y = config.getInt("y", -1);
        int divider = config.getInt("divider", 320);
        boolean alwaysOnTop = config.getBool("alwaysOnTopWindow", false);

        frame.setSize(w, h);
        frame.setAlwaysOnTop(alwaysOnTop);
        splitPane.setDividerLocation(divider);

        if (x >= 0 && y >= 0) frame.setLocation(x, y);
        else frame.setLocationRelativeTo(null);

        String last = config.getString("lastNoteId", null);
        if (last != null) selectByIdOrFirst(last);
        else selectByIdOrFirst(currentNoteId);
    }

    private void saveWindowConfig() {
        if (frame == null) return;
        config.setInt("w", frame.getWidth());
        config.setInt("h", frame.getHeight());
        config.setInt("x", frame.getX());
        config.setInt("y", frame.getY());
        config.setInt("divider", splitPane.getDividerLocation());
        config.save();
    }

    private static void applyTheme(Theme t) {
        try {
            if (t == Theme.LIGHT) FlatLightLaf.setup();
            else if (t == Theme.DARK) FlatDarkLaf.setup();
            else UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
    }

    private void focusEditor() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                editor.requestFocusInWindow();
            }
        });
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
