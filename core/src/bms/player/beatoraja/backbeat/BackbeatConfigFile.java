package bms.player.beatoraja.backbeat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读取和写入 Backbeat 自己的 backbeat.toml。
 *
 * Backbeat SDK 没有提供 store 路径参数，store 位置只能来自这个固定位置的配置文件。
 * 这里只处理 store.path，其余内容原样保留。
 */
public final class BackbeatConfigFile {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BackbeatConfigFile() {
    }

    /**
     * backbeat.toml 的固定位置。Backbeat 规范规定配置文件位置不可配置。
     */
    public static Path configPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) {
                return null;
            }
            return Path.of(appData, "backbeat", "backbeat.toml");
        }
        String home = System.getProperty("user.home");
        if (os.contains("mac")) {
            return Path.of(home, ".config", "backbeat", "backbeat.toml");
        }
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        Path configHome = xdgConfig == null || xdgConfig.isBlank()
                ? Path.of(home, ".config")
                : Path.of(xdgConfig);
        return configHome.resolve("backbeat").resolve("backbeat.toml");
    }

    public static Optional<String> readStorePath() throws IOException {
        Path path = configPath();
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }

        boolean inStore = false;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inStore = line.equals("[store]");
                continue;
            }
            if (inStore && line.startsWith("path") && line.matches("^path\\s*=.*")) {
                int equals = line.indexOf('=');
                String value = unquote(stripComment(line.substring(equals + 1)).trim());
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * 写入 store.path。已有文件先备份，其他键和所有 [[server]] 保持不变。
     */
    public static void writeStorePath(String storePath) throws IOException {
        if (storePath == null || storePath.isBlank()) {
            return;
        }
        Path path = configPath();
        if (path == null) {
            throw new IOException("cannot locate backbeat.toml on this operating system");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> lines = Files.isRegularFile(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                : new ArrayList<>();

        if (Files.isRegularFile(path)) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak-"
                    + LocalDateTime.now().format(BACKUP_TIME));
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        String encoded = tomlString(storePath);
        boolean inStore = false;
        boolean hasStore = false;
        boolean replaced = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inStore = trimmed.equals("[store]");
                hasStore |= inStore;
                continue;
            }
            if (inStore && trimmed.matches("^path\\s*=.*")) {
                int equals = line.indexOf('=');
                lines.set(i, line.substring(0, equals + 1) + " " + encoded);
                replaced = true;
            }
        }

        if (!replaced) {
            if (hasStore) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().equals("[store]")) {
                        lines.add(i + 1, "path = " + encoded);
                        break;
                    }
                }
            } else {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.add("[store]");
                lines.add("path = " + encoded);
            }
        }

        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String tomlString(String value) {
        // TOML 单引号字面量最适合 Windows 路径；只有路径本身含单引号时才改用双引号。
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1);
        }
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1)
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"");
        }
        return value;
    }

    private static String stripComment(String value) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && doubleQuote) {
                escaped = true;
            } else if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            } else if (c == '#' && !singleQuote && !doubleQuote) {
                return value.substring(0, i);
            }
        }
        return value;
    }
}
