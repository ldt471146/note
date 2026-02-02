import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public final class AppConfig {
    private final Properties properties = new Properties();
    private final Path file;

    public AppConfig(Path file) {
        this.file = file;
    }

    public void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {}
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(out, "StickyNoteApp config");
            }
        } catch (IOException ignored) {}
    }

    public int getInt(String key, int fallback) {
        String v = properties.getProperty(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        String v = properties.getProperty(key);
        if (v == null) return fallback;
        return Boolean.parseBoolean(v.trim());
    }

    public String getString(String key, String fallback) {
        String v = properties.getProperty(key);
        return v == null ? fallback : v;
    }

    public void setInt(String key, int value) {
        properties.setProperty(key, Integer.toString(value));
    }

    public void setBool(String key, boolean value) {
        properties.setProperty(key, Boolean.toString(value));
    }

    public void setString(String key, String value) {
        if (value == null) properties.remove(key);
        else properties.setProperty(key, value);
    }
}
