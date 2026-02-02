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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class StickyNoteApp {
    private enum Theme { SYSTEM, LIGHT, DARK }
    private enum Scope { ACTIVE, ARCHIVED, ALL, TRASH }
    private enum EditorMode { EDIT, SPLIT, PREVIEW }

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
    private final Timer previewTimer;
    private final Timer searchTimer;
    private final UndoManager undoManager = new UndoManager();

    private JFrame frame;
    private JSplitPane splitPane;
    private JTabbedPane editorTabs;
    private JScrollPane editorScroll;
    private JScrollPane previewScroll;
    private JSplitPane splitPreviewPane;
    private boolean splitPreviewListenerInstalled = false;
    private JPanel editorWrapper;
    private JPanel tagChipsPanel;
    private JButton addTagButton;
    private boolean suppressEditorTabEvents = false;
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

        previewTimer = new Timer(250, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                renderPreviewIfVisible();
            }
        });
        previewTimer.setRepeats(false);

        searchTimer = new Timer(120, new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                reloadListOnly();
            }
        });
        searchTimer.setRepeats(false);
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
        installListContextMenu();

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
        previewPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (Exception ignored) {}
            }
        });

        searchField.putClientProperty("JTextField.placeholderText", "搜索标题/内容/标签…");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { searchTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { searchTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { searchTimer.restart(); }
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
        editorTabs = new JTabbedPane();

        editorWrapper = new JPanel(new BorderLayout());
        tagChipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        tagChipsPanel.setOpaque(false);
        tagChipsPanel.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 10));
        editorWrapper.add(tagChipsPanel, BorderLayout.NORTH);

        editorScroll = new JScrollPane(editor);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        editorWrapper.add(editorScroll, BorderLayout.CENTER);

        previewScroll = new JScrollPane(previewPane);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel editPanel = new JPanel(new BorderLayout());
        JPanel splitPanel = new JPanel(new BorderLayout());
        JPanel previewPanel = new JPanel(new BorderLayout());

        editorTabs.addTab("编辑", editPanel);
        editorTabs.addTab("分屏", splitPanel);
        editorTabs.addTab("预览", previewPanel);

        splitPreviewPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPreviewPane.setContinuousLayout(true);
        splitPreviewPane.setDividerSize(10);
        splitPanel.add(splitPreviewPane, BorderLayout.CENTER);
        if (!splitPreviewListenerInstalled) {
            splitPreviewPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
                config.setInt("previewDivider", splitPreviewPane.getDividerLocation());
                config.save();
            });
            splitPreviewListenerInstalled = true;
        }

        editorTabs.addChangeListener(e -> {
            if (suppressEditorTabEvents) return;
            EditorMode mode = editorModeFromTabIndex(editorTabs.getSelectedIndex());
            applyEditorMode(mode);
        });

        EditorMode mode = EditorMode.valueOf(config.getString("editorMode", EditorMode.EDIT.name()));
        applyEditorMode(mode);
        return editorTabs;
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
        tb.addSeparator();
        tb.add(button("回收站", e -> actionShowTrash()));

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
        file.add(item("备份导出…", null, e -> actionBackupExport()));
        file.add(item("备份导入…", null, e -> actionBackupImport()));
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
        view.add(item("回收站", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask()), e -> actionShowTrash()));
        view.add(item("清空回收站…", null, e -> actionEmptyTrash()));

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

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, menuMask()), "cycleEditorMode");
        am.put("cycleEditorMode", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { actionCycleEditorMode(); }
        });
    }

    private void onEditorChanged() {
        if (suppressDocEvents) return;
        dirty = true;
        statusLeft.setText("未保存…");
        updateCounts();
        autoSaveTimer.restart();
        if (shouldLivePreview()) previewTimer.restart();
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
            boolean editable = n != null && !n.deleted;
            editor.setEditable(editable);
            editor.setEnabled(true);
            currentNoteId = n == null ? null : n.id;
            dirty = false;
            statusLeft.setText("已加载");
            updateCounts();
            rebuildTagChips(n);
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
            if (shouldLivePreview()) previewTimer.restart();
        } catch (IOException e) {
            statusLeft.setText("保存失败：" + e.getMessage());
        }
    }

    private void reloadFiltersAndList() {
        Scope scope = (Scope) scopeBox.getSelectedItem();

        tagBox.removeAllItems();
        tagBox.addItem("（全部标签）");
        List<String> tags = new ArrayList<String>(collectTagsForScope(scope));
        Collections.sort(tags);
        for (int i = 0; i < tags.size(); i++) tagBox.addItem(tags.get(i));

        reloadListOnly();
    }

    private java.util.Set<String> collectTagsForScope(Scope scope) {
        java.util.Set<String> tags = new java.util.HashSet<String>();
        List<Note> all = store.getAll();
        for (int i = 0; i < all.size(); i++) {
            Note n = all.get(i);
            if (n == null) continue;
            if (scope == Scope.TRASH) {
                if (!n.deleted) continue;
            } else {
                if (n.deleted) continue;
                if (scope == Scope.ACTIVE && n.archived) continue;
                if (scope == Scope.ARCHIVED && !n.archived) continue;
            }
            if (n.tags == null) continue;
            for (int t = 0; t < n.tags.size(); t++) {
                String tag = NoteStore.normalizeTag(n.tags.get(t));
                if (tag.length() > 0) tags.add(tag);
            }
        }
        return tags;
    }

    private void reloadListOnly() {
        reloadListOnlyPreserveSelection(currentNoteId);
    }

    private void reloadListOnlyPreserveSelection(String keepId) {
        listModel.clear();

        Scope scope = (Scope) scopeBox.getSelectedItem();
        String q = searchField.getText();
        String tag = selectedTag();
        noteList.putClientProperty("query", q);

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
            if (scope == Scope.TRASH) {
                if (!n.deleted) continue;
            } else {
                if (n.deleted) continue;
                if (scope == Scope.ACTIVE && n.archived) continue;
                if (scope == Scope.ARCHIVED && !n.archived) continue;
            }
            if (!n.matchesQuery(q)) continue;
            if (tag != null && tag.length() > 0 && !hasTag(n, tag)) continue;
            listModel.addElement(n);
        }

        selectByIdOrFirst(keepId);
        noteList.repaint();
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
        if (n.deleted) {
            int ok = JOptionPane.showConfirmDialog(frame, "确定要永久删除这条便签吗？（不可恢复）", "永久删除", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
            try {
                store.deletePermanently(n.id);
                currentNoteId = null;
                dirty = false;
                reloadFiltersAndList();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, e.toString(), "删除失败", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        int ok = JOptionPane.showConfirmDialog(frame, "确定要将这条便签移入回收站吗？", "移入回收站", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            store.moveToTrash(n.id);
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
        if (n.deleted) return;
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
        if (n.deleted) return;
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
        if (n.deleted) return;
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
            rebuildTagChips(n);
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private void actionShowTrash() {
        scopeBox.setSelectedItem(Scope.TRASH);
        reloadFiltersAndList();
    }

    private void actionRestoreSelected() {
        Note n = selectedNote();
        if (n == null || !n.deleted) return;
        try {
            store.restoreFromTrash(n.id);
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private void actionEmptyTrash() {
        int ok = JOptionPane.showConfirmDialog(frame, "确定要清空回收站吗？（不可恢复）", "清空回收站", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            store.emptyTrash();
            currentNoteId = null;
            dirty = false;
            reloadFiltersAndList();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "清空失败", JOptionPane.ERROR_MESSAGE);
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

    private void actionBackupExport() {
        saveIfDirty(false);
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("sticky-note-backup.zip"));
        int ok = fc.showSaveDialog(frame);
        if (ok != JFileChooser.APPROVE_OPTION) return;
        Path file = fc.getSelectedFile().toPath();
        try {
            writeBackupZip(file);
            statusLeft.setText("已备份导出：" + file.getFileName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "备份导出失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionBackupImport() {
        saveIfDirty(false);
        JFileChooser fc = new JFileChooser();
        int ok = fc.showOpenDialog(frame);
        if (ok != JFileChooser.APPROVE_OPTION) return;
        Path file = fc.getSelectedFile().toPath();

        int sure = JOptionPane.showConfirmDialog(frame,
                "导入备份会覆盖当前数据（notes.json），确定继续吗？",
                "确认导入备份",
                JOptionPane.OK_CANCEL_OPTION);
        if (sure != JOptionPane.OK_OPTION) return;

        try {
            readBackupZip(file);
            store.ensureLoaded();
            reloadFiltersAndList();
            selectByIdOrFirst(currentNoteId);
            statusLeft.setText("已导入备份：" + file.getFileName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.toString(), "备份导入失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void writeBackupZip(Path zipFile) throws IOException {
        Files.createDirectories(zipFile.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(zipFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            if (Files.exists(paths.notesFile)) addZipFile(zos, "notes.json", paths.notesFile);
            if (Files.exists(paths.configFile)) addZipFile(zos, "config.properties", paths.configFile);
            if (Files.isDirectory(paths.historyDir)) addZipDir(zos, "history/", paths.historyDir);
        }
    }

    private void readBackupZip(Path zipFile) throws IOException {
        if (!Files.exists(zipFile)) throw new IOException("File not found: " + zipFile);
        Files.createDirectories(paths.appDir);
        Files.createDirectories(paths.historyDir);

        Path tmp = paths.appDir.resolve("import_tmp");
        if (Files.exists(tmp)) deleteRecursively(tmp);
        Files.createDirectories(tmp);

        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains(":")) continue;
                Path out = tmp.resolve(name.replace("/", java.io.File.separator));
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path notes = tmp.resolve("notes.json");
        Path cfg = tmp.resolve("config.properties");
        if (Files.exists(notes)) Files.copy(notes, paths.notesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(cfg)) Files.copy(cfg, paths.configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Path hist = tmp.resolve("history");
        if (Files.isDirectory(hist)) {
            if (Files.exists(paths.historyDir)) deleteRecursively(paths.historyDir);
            Files.createDirectories(paths.historyDir);
            copyRecursively(hist, paths.historyDir);
        }

        deleteRecursively(tmp);
    }

    private static void addZipFile(ZipOutputStream zos, String entryName, Path file) throws IOException {
        ZipEntry e = new ZipEntry(entryName);
        zos.putNextEntry(e);
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    private static void addZipDir(ZipOutputStream zos, String prefix, Path dir) throws IOException {
        java.util.ArrayDeque<Path> stack = new java.util.ArrayDeque<Path>();
        stack.push(dir);
        while (!stack.isEmpty()) {
            Path cur = stack.pop();
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(cur)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) stack.push(p);
                    else {
                        Path rel = dir.relativize(p);
                        String name = prefix + rel.toString().replace(java.io.File.separatorChar, '/');
                        addZipFile(zos, name, p);
                    }
                }
            }
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        java.util.ArrayDeque<Path> stack = new java.util.ArrayDeque<Path>();
        stack.push(from);
        while (!stack.isEmpty()) {
            Path cur = stack.pop();
            Path rel = from.relativize(cur);
            Path dst = to.resolve(rel.toString());
            if (Files.isDirectory(cur)) {
                Files.createDirectories(dst);
                try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(cur)) {
                    for (Path p : ds) stack.push(p);
                }
            } else {
                Files.createDirectories(dst.getParent());
                Files.copy(cur, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        java.util.ArrayDeque<Path> stack = new java.util.ArrayDeque<Path>();
        java.util.ArrayList<Path> all = new java.util.ArrayList<Path>();
        stack.push(p);
        while (!stack.isEmpty()) {
            Path cur = stack.pop();
            all.add(cur);
            if (Files.isDirectory(cur)) {
                try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(cur)) {
                    for (Path c : ds) stack.push(c);
                }
            }
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            try { Files.deleteIfExists(all.get(i)); } catch (IOException ignored) {}
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

    private void renderPreviewIfVisible() {
        if (editorTabs == null) return;
        if (!shouldLivePreview()) return;
        markdownPreview.renderTo(previewPane, editor.getText());
    }

    private boolean shouldLivePreview() {
        if (editorTabs == null) return false;
        EditorMode mode = editorModeFromTabIndex(editorTabs.getSelectedIndex());
        return mode == EditorMode.SPLIT || mode == EditorMode.PREVIEW;
    }

    private void actionCycleEditorMode() {
        if (editorTabs == null) return;
        int idx = editorTabs.getSelectedIndex();
        int next = (idx + 1) % editorTabs.getTabCount();
        editorTabs.setSelectedIndex(next);
    }

    private void applyEditorMode(EditorMode mode) {
        if (editorTabs == null) return;
        suppressEditorTabEvents = true;
        try {
            editorTabs.setSelectedIndex(tabIndexFromEditorMode(mode));
        } finally {
            suppressEditorTabEvents = false;
        }
        config.setString("editorMode", mode.name());
        config.save();

        JPanel editPanel = (JPanel) editorTabs.getComponentAt(0);
        JPanel splitPanel = (JPanel) editorTabs.getComponentAt(1);
        JPanel previewPanel = (JPanel) editorTabs.getComponentAt(2);

        editPanel.removeAll();
        previewPanel.removeAll();
        splitPreviewPane.setLeftComponent(null);
        splitPreviewPane.setRightComponent(null);

        if (mode == EditorMode.EDIT) {
            editPanel.add(editorWrapper, BorderLayout.CENTER);
        } else if (mode == EditorMode.PREVIEW) {
            previewPanel.add(previewScroll, BorderLayout.CENTER);
        } else {
            splitPreviewPane.setLeftComponent(editorWrapper);
            splitPreviewPane.setRightComponent(previewScroll);
            int div = config.getInt("previewDivider", -1);
            if (div > 0) splitPreviewPane.setDividerLocation(div);
        }

        editorTabs.revalidate();
        editorTabs.repaint();

        if (mode == EditorMode.SPLIT || mode == EditorMode.PREVIEW) previewTimer.restart();
        else focusEditor();
    }

    private static EditorMode editorModeFromTabIndex(int idx) {
        if (idx == 1) return EditorMode.SPLIT;
        if (idx == 2) return EditorMode.PREVIEW;
        return EditorMode.EDIT;
    }

    private static int tabIndexFromEditorMode(EditorMode mode) {
        if (mode == EditorMode.SPLIT) return 1;
        if (mode == EditorMode.PREVIEW) return 2;
        return 0;
    }

    private void rebuildTagChips(Note note) {
        if (tagChipsPanel == null) return;
        tagChipsPanel.removeAll();

        addTagButton = new JButton("＋ 标签");
        addTagButton.setFocusable(false);
        addTagButton.addActionListener(e -> actionQuickAddTag());

        boolean editable = note != null && !note.deleted;
        addTagButton.setEnabled(editable);

        if (note != null && note.tags != null && !note.tags.isEmpty()) {
            for (int i = 0; i < note.tags.size(); i++) {
                final String tag = NoteStore.normalizeTag(note.tags.get(i));
                if (tag.length() == 0) continue;
                JButton chip = new JButton("#" + tag + "  ×");
                chip.setFocusable(false);
                chip.setEnabled(editable);
                chip.addActionListener(e -> actionRemoveTag(tag));
                tagChipsPanel.add(chip);
            }
            tagChipsPanel.add(Box.createHorizontalStrut(6));
        }

        tagChipsPanel.add(addTagButton);
        tagChipsPanel.revalidate();
        tagChipsPanel.repaint();
    }

    private void actionQuickAddTag() {
        Note n = selectedNote();
        if (n == null || n.deleted) return;
        String input = JOptionPane.showInputDialog(frame, "输入一个标签（不需要 #）", "添加标签", JOptionPane.PLAIN_MESSAGE);
        if (input == null) return;
        String t = NoteStore.normalizeTag(input);
        if (t.length() == 0) return;
        if (n.tags == null) n.tags = new ArrayList<String>();
        if (!containsIgnoreCase(n.tags, t)) n.tags.add(t);
        try {
            store.updateNote(n, false);
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
            rebuildTagChips(n);
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private void actionRemoveTag(String tag) {
        Note n = selectedNote();
        if (n == null || n.deleted) return;
        if (n.tags == null || n.tags.isEmpty()) return;
        boolean changed = false;
        for (int i = n.tags.size() - 1; i >= 0; i--) {
            if (tag.equalsIgnoreCase(n.tags.get(i))) {
                n.tags.remove(i);
                changed = true;
            }
        }
        if (!changed) return;
        try {
            store.updateNote(n, false);
            reloadFiltersAndList();
            selectByIdOrFirst(n.id);
            rebuildTagChips(n);
        } catch (IOException e) {
            statusLeft.setText("操作失败：" + e.getMessage());
        }
    }

    private void installListContextMenu() {
        final JPopupMenu menu = new JPopupMenu();
        JMenuItem restore = item("从回收站还原", null, e -> actionRestoreSelected());
        JMenuItem del = item("删除", null, e -> actionDelete());
        JMenuItem pin = item("置顶/取消置顶", null, e -> actionTogglePinned());
        JMenuItem arch = item("归档/取消归档", null, e -> actionToggleArchived());
        JMenuItem tags = item("编辑标签…", null, e -> actionEditTags());

        menu.add(restore);
        menu.addSeparator();
        menu.add(pin);
        menu.add(arch);
        menu.add(tags);
        menu.addSeparator();
        menu.add(del);

        noteList.addMouseListener(new MouseAdapter() {
            private void showIfPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = noteList.locationToIndex(e.getPoint());
                if (idx >= 0) noteList.setSelectedIndex(idx);
                Note n = noteList.getSelectedValue();
                boolean isTrash = n != null && n.deleted;
                restore.setVisible(isTrash);
                pin.setEnabled(!isTrash);
                arch.setEnabled(!isTrash);
                tags.setEnabled(!isTrash);
                menu.show(noteList, e.getX(), e.getY());
            }

            @Override public void mousePressed(MouseEvent e) { showIfPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { showIfPopup(e); }
        });
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
