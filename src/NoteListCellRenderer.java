import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class NoteListCellRenderer extends JPanel implements ListCellRenderer<Note> {
    private final JLabel titleLabel = new JLabel();
    private final JLabel metaLabel = new JLabel();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);

    public NoteListCellRenderer() {
        setLayout(new BorderLayout(6, 2));
        setBorder(new EmptyBorder(8, 10, 8, 10));

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 0.5f));
        metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, metaLabel.getFont().getSize2D() - 1f));
        metaLabel.setForeground(new Color(110, 110, 110));

        add(titleLabel, BorderLayout.NORTH);
        add(metaLabel, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Note> list, Note value, int index, boolean isSelected, boolean cellHasFocus) {
        String title = value == null ? "" : value.title();
        StringBuilder meta = new StringBuilder();
        if (value != null) {
            if (value.pinned) meta.append("置顶  ");
            if (value.archived) meta.append("归档  ");
            String tags = value.tagsJoined();
            if (tags.length() > 0) meta.append("#").append(tags.replace(", ", " #")).append("  ");
            meta.append(fmt.format(new Date(value.updatedAt)));
        }
        titleLabel.setText(title);
        metaLabel.setText(meta.toString());

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
        metaLabel.setForeground(isSelected ? fg : UIManager.getColor("Label.disabledForeground"));
        return this;
    }
}
