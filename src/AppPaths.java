import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {
    private static final String APP_DIR_NAME = ".sticky-note-app";

    public final Path appDir;
    public final Path notesFile;
    public final Path configFile;
    public final Path historyDir;
    public final Path legacyNoteFile;

    public AppPaths() {
        String home = System.getProperty("user.home");
        appDir = Paths.get(home, APP_DIR_NAME);
        notesFile = appDir.resolve("notes.json");
        configFile = appDir.resolve("config.properties");
        historyDir = appDir.resolve("history");
        legacyNoteFile = appDir.resolve("note.txt");
    }
}
