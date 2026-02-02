import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NoteListCellRenderer extends JPanel implements ListCellRenderer<Note> {
    private final JLabel titleLabel = new JLabel();
    private final JLabel snippetLabel = new JLabel();
    private final JLabel metaLabel = new JLabel();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);

    public NoteListCellRenderer() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel lines = new JPanel();
        lines.setOpaque(false);
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 0.5f));

        snippetLabel.setFont(snippetLabel.getFont().deriveFont(Font.PLAIN, snippetLabel.getFont().getSize2D() - 0.5f));
        snippetLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, metaLabel.getFont().getSize2D() - 1.5f));
        metaLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        lines.add(titleLabel);
        lines.add(Box.createVerticalStrut(3));
        lines.add(snippetLabel);
        lines.add(Box.createVerticalStrut(6));
        lines.add(metaLabel);
        add(lines, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Note> list, Note value, int index, boolean isSelected, boolean cellHasFocus) {
        String query = "";
        Object q = list.getClientProperty("query");
        if (q != null) query = q.toString();

        String title = value == null ? "" : value.title();
        String snippet = value == null ? "" : value.snippet();
        StringBuilder meta = new StringBuilder();
        if (value != null) {
            if (value.deleted) meta.append("回收站  ");
            else {
                if (value.pinned) meta.append("置顶  ");
                if (value.archived) meta.append("归档  ");
            }
            String tags = value.tagsJoined();
            if (tags.length() > 0) meta.append("#").append(tags.replace(", ", " #")).append("  ");
            meta.append(fmt.format(new Date(value.updatedAt)));
        }

        boolean dark = isDark(list.getBackground());
        String hl = dark ? "#3a3f2a" : "#fff4a3";
        titleLabel.setText(toHtml(highlight(escapeHtml(title), query, hl)));
        snippetLabel.setText(toHtml(highlight(escapeHtml(snippet), query, hl)));
        metaLabel.setText(toHtml(highlight(escapeHtml(meta.toString()), query, hl)));

        Color bg;
        Color fg;
        if (isSelected) {
            bg = list.getSelectionBackground();
            fg = list.getSelectionForeground();
        } else {
            bg = list.getBackground();
            fg = list.getForeground();
        }
        setBackground(bg);
        titleLabel.setForeground(fg);
        snippetLabel.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        metaLabel.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        return this;
    }

    private static String toHtml(String inner) {
        return "<html><body style='margin:0;padding:0'>" + inner + "</body></html>";
    }

    private static boolean isDark(Color c) {
        if (c == null) return false;
        int r = c.getRed();
        int g = c.getGreen();
        int b = c.getBlue();
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum < 128;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c == '\'') sb.append("&#39;");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String highlight(String escapedText, String query, String hlColor) {
        if (escapedText == null) return "";
        if (query == null) return escapedText;
        query = query.trim();
        if (query.length() == 0) return escapedText;

        String lowerText = escapedText.toLowerCase(Locale.ROOT);
        String lowerQ = escapeHtml(query).toLowerCase(Locale.ROOT);
        if (lowerQ.length() == 0) return escapedText;

        StringBuilder out = new StringBuilder(escapedText.length() + 32);
        int from = 0;
        int hits = 0;
        while (from < escapedText.length()) {
            int idx = lowerText.indexOf(lowerQ, from);
            if (idx < 0) break;
            out.append(escapedText, from, idx);
            int end = idx + lowerQ.length();
            out.append("<span style='background:").append(hlColor).append(";border-radius:3px;padding:0 2px'>");
            out.append(escapedText, idx, end);
            out.append("</span>");
            from = end;
            hits++;
            if (hits >= 8) break;
        }
        out.append(escapedText.substring(from));
        return out.toString();
    }
}
