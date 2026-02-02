import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupZip {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Path defaultBackupFile(Path dir) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date(System.currentTimeMillis()));
        return dir.resolve("sticky-note-backup_" + ts + ".zip");
    }

    public void exportBackup(Path zipFile, List<Note> notes) throws IOException {
        if (zipFile == null) throw new IOException("zipFile is null");
        if (notes == null) notes = new ArrayList<Note>();
        Files.createDirectories(zipFile.toAbsolutePath().getParent());

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            ZipEntry meta = new ZipEntry("meta.txt");
            zos.putNextEntry(meta);
            zos.write(("StickyNoteApp backup\nexportedAt=" + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry entry = new ZipEntry("notes.json");
            zos.putNextEntry(entry);
            byte[] bytes = gson.toJson(notes).getBytes(StandardCharsets.UTF_8);
            zos.write(bytes);
            zos.closeEntry();
        }
    }

    public List<Note> importBackup(Path zipFile) throws IOException {
        if (zipFile == null) throw new IOException("zipFile is null");
        if (!Files.exists(zipFile)) throw new IOException("File not found: " + zipFile);

        byte[] notesJson = null;
        try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if ("notes.json".equalsIgnoreCase(name) || name.endsWith("/notes.json")) {
                    notesJson = readAll(zis);
                }
                zis.closeEntry();
            }
        }

        if (notesJson == null) throw new IOException("Backup zip missing notes.json");
        try {
            String json = new String(notesJson, StandardCharsets.UTF_8);
            Note[] arr = gson.fromJson(json, Note[].class);
            List<Note> out = new ArrayList<Note>();
            if (arr != null) {
                for (int i = 0; i < arr.length; i++) {
                    Note n = arr[i];
                    if (n == null || n.id == null) continue;
                    if (n.tags == null) n.tags = new ArrayList<String>();
                    if (n.deletedAt < 0L) n.deletedAt = 0L;
                    out.add(n);
                }
            }
            return out;
        } catch (JsonParseException e) {
            throw new IOException("Invalid backup notes.json", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}

