import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class NoteStore {
    private static final int MAX_HISTORY_FILES_PER_NOTE = 50;

    private final AppPaths paths;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final List<Note> notes = new ArrayList<Note>();

    public NoteStore(AppPaths paths) {
        this.paths = paths;
    }

    public void ensureLoaded() throws IOException {
        Files.createDirectories(paths.appDir);
        Files.createDirectories(paths.historyDir);

        if (Files.exists(paths.notesFile)) {
            loadFromJson();
            if (!notes.isEmpty()) return;
        }

        if (Files.exists(paths.legacyNoteFile)) {
            String legacy = readUtf8(paths.legacyNoteFile);
            Note imported = Note.createEmpty();
            imported.content = legacy == null ? "" : legacy;
            imported.updatedAt = System.currentTimeMillis();
            imported.createdAt = imported.updatedAt;
            imported.tags.add("旧便签");
            notes.add(imported);
            saveAll();
            return;
        }

        notes.add(Note.createEmpty());
        saveAll();
    }

    public List<Note> getAll() {
        return notes;
    }

    public Note getById(String id) {
        if (id == null) return null;
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (id.equals(n.id)) return n;
        }
        return null;
    }

    public Note createNote() throws IOException {
        Note n = Note.createEmpty();
        notes.add(n);
        saveAll();
        return n;
    }

    public void deleteNote(String id) throws IOException {
        if (id == null) return;
        for (int i = 0; i < notes.size(); i++) {
            if (id.equals(notes.get(i).id)) {
                notes.remove(i);
                break;
            }
        }
        if (notes.isEmpty()) notes.add(Note.createEmpty());
        saveAll();
    }

    public void updateNote(Note note, boolean writeHistory) throws IOException {
        if (note == null) return;
        note.updatedAt = System.currentTimeMillis();
        if (writeHistory) writeHistorySnapshot(note);
        saveAll();
    }

    public Set<String> collectTags(boolean includeArchived) {
        Set<String> tags = new HashSet<String>();
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (!includeArchived && n.archived) continue;
            if (n.tags == null) continue;
            for (int t = 0; t < n.tags.size(); t++) {
                String tag = normalizeTag(n.tags.get(t));
                if (tag.length() > 0) tags.add(tag);
            }
        }
        return tags;
    }

    public List<Path> listHistoryFiles(String noteId) {
        Path dir = paths.historyDir.resolve(noteId);
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<Path> result = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : ds) result.add(p);
        } catch (IOException ignored) {}
        Collections.sort(result, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                return b.getFileName().toString().compareTo(a.getFileName().toString());
            }
        });
        return result;
    }

    public String readHistoryFile(Path p) throws IOException {
        return readUtf8(p);
    }

    public void saveAll() throws IOException {
        Files.createDirectories(paths.appDir);
        String json = gson.toJson(notes);

        Path tmp = paths.notesFile.resolveSibling("notes.json.tmp");
        Path bak = paths.notesFile.resolveSibling("notes.json.bak");

        if (Files.exists(paths.notesFile)) {
            try {
                Files.copy(paths.notesFile, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }

        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, paths.notesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void loadFromJson() throws IOException {
        try {
            String json = readUtf8(paths.notesFile);
            if (json == null || json.trim().length() == 0) return;
            Note[] arr = gson.fromJson(json, Note[].class);
            notes.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length; i++) {
                    Note n = arr[i];
                    if (n == null || n.id == null) continue;
                    if (n.tags == null) n.tags = new ArrayList<String>();
                    notes.add(n);
                }
            }
        } catch (JsonParseException e) {
            // Keep empty; caller will handle fallback creation.
        }
    }

    private void writeHistorySnapshot(Note note) {
        if (note == null || note.id == null) return;
        try {
            Path dir = paths.historyDir.resolve(note.id);
            Files.createDirectories(dir);
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date(System.currentTimeMillis()));
            Path file = dir.resolve(ts + ".txt");
            Files.write(file, safeString(note.content).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            trimHistory(dir);
        } catch (IOException ignored) {}
    }

    private void trimHistory(Path dir) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                return b.getFileName().toString().compareTo(a.getFileName().toString());
            }
        });
        for (int i = MAX_HISTORY_FILES_PER_NOTE; i < files.size(); i++) {
            try { Files.deleteIfExists(files.get(i)); } catch (IOException ignored) {}
        }
    }

    public static String normalizeTag(String s) {
        if (s == null) return "";
        s = s.trim().replace('\u3000', ' ');
        while (s.startsWith("#")) s = s.substring(1).trim();
        if (s.length() > 24) s = s.substring(0, 24);
        return s;
    }

    private static String readUtf8(Path p) throws IOException {
        if (!Files.exists(p)) return null;
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }
}
