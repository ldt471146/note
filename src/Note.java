import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class Note {
    public String id;
    public String content;
    public List<String> tags;
    public boolean pinned;
    public boolean archived;
    public boolean deleted;
    public long deletedAt;
    public long createdAt;
    public long updatedAt;

    public Note() {}

    public static Note createEmpty() {
        Note n = new Note();
        n.id = UUID.randomUUID().toString();
        n.content = "";
        n.tags = new ArrayList<String>();
        n.pinned = false;
        n.archived = false;
        n.deleted = false;
        n.deletedAt = 0L;
        long now = System.currentTimeMillis();
        n.createdAt = now;
        n.updatedAt = now;
        return n;
    }

    public String title() {
        String t = firstNonEmptyLine(content);
        if (t.length() == 0) return "（无标题）";
        t = t.replace('\t', ' ').trim();
        if (t.length() > 36) t = t.substring(0, 36) + "…";
        return t;
    }

    public String snippet() {
        String s = content == null ? "" : content.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        if (s.length() > 90) s = s.substring(0, 90) + "…";
        return s;
    }

    public boolean matchesQuery(String q) {
        if (q == null) return true;
        q = q.trim();
        if (q.length() == 0) return true;
        String needle = q.toLowerCase(Locale.ROOT);
        String hay = (title() + "\n" + tagsJoined() + "\n" + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        return hay.contains(needle);
    }

    public String tagsJoined() {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(tags.get(i));
        }
        return sb.toString();
    }

    private static String firstNonEmptyLine(String s) {
        if (s == null) return "";
        String[] lines = s.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0) return line;
        }
        return "";
    }
}
