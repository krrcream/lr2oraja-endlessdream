package bms.player.beatoraja.modmenu;

import ac.backbeat.sdk.DataId;
import ac.backbeat.sdk.DownloadProgress;
import ac.backbeat.sdk.DownloadSnapshot;
import ac.backbeat.sdk.DownloadState;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainLoader;
import bms.player.beatoraja.backbeat.BackbeatIntegration;
import bms.player.beatoraja.song.SongData;
import com.badlogic.gdx.Gdx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backbeat 单曲下载任务。下载由 SDK 在 native 层执行，这里只负责发起、
 * 轮询进度，以及下载完成后把新歌同步进 songdb。
 */
public final class BackbeatDownloadState {

    private static final Logger logger = LoggerFactory.getLogger(BackbeatDownloadState.class);
    private static final long POLL_INTERVAL_NS = 250_000_000L;

    private static final Map<String, Task> tasks = new LinkedHashMap<>();
    private static long lastPoll;

    private BackbeatDownloadState() {
    }

    public static final class Task {
        public final String chartId;
        public final String title;
        private final MainController controller;
        private final Runnable onComplete;

        public DownloadState state = DownloadState.QUEUED;
        public long bytes;
        public long total = -1;
        public String error = "";
        public boolean synced;
        public boolean syncFailed;

        private boolean syncStarted;
        private boolean errorNotified;

        private Task(String chartId, String title, MainController controller, Runnable onComplete) {
            this.chartId = chartId;
            this.title = title;
            this.controller = controller;
            this.onComplete = onComplete;
        }
    }

    public static List<Task> tasks() {
        synchronized (tasks) {
            return new ArrayList<>(tasks.values());
        }
    }

    public static boolean hasTasks() {
        synchronized (tasks) {
            return !tasks.isEmpty();
        }
    }

    public static void start(MainController controller, SongData song, Runnable onComplete) {
        String chartId = chartId(song);
        if (chartId == null) {
            ImGuiNotify.error("Backbeat: this chart has no md5/sha256 and cannot be downloaded.");
            return;
        }
        String title = title(song);

        synchronized (tasks) {
            Task existing = tasks.get(chartId);
            if (existing != null && existing.state != DownloadState.FAILED
                    && existing.state != DownloadState.CANCELLED) {
                return;
            }

            Task task = new Task(chartId, title, controller, onComplete);
            tasks.put(chartId, task);

            try {
                BackbeatIntegration integration = MainLoader.getBackbeatIntegration();
                String server = controller.getConfig().getBackbeatServerUrl();
                integration.addServer(server);
                if (integration.hasZeroDataServers()) {
                    task.state = DownloadState.FAILED;
                    task.error = "no Backbeat data server configured";
                    notifyError(task);
                    return;
                }
                if (integration.hasChart(chartId)) {
                    task.state = DownloadState.DONE;
                } else {
                    integration.downloadChart(chartId);
                }
            } catch (RuntimeException error) {
                task.state = DownloadState.FAILED;
                task.error = message(error);
                notifyError(task);
                return;
            }
        }

        ImGuiNotify.info("Backbeat: downloading " + title);
    }

    public static void update(MainController controller) {
        long now = System.nanoTime();
        if (now - lastPoll < POLL_INTERVAL_NS) {
            return;
        }
        lastPoll = now;

        List<Task> snapshot;
        synchronized (tasks) {
            if (tasks.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(tasks.values());
        }

        Map<String, DownloadSnapshot> downloads = new HashMap<>();
        try {
            for (DownloadSnapshot download : MainLoader.getBackbeatIntegration().downloadSnapshots()) {
                if (download.key().kind() == DataId.Kind.CHART) {
                    downloads.put(download.key().value(), download);
                }
            }
        } catch (RuntimeException error) {
            logger.warn("Failed to read Backbeat download progress: {}", message(error));
        }

        for (Task task : snapshot) {
            DownloadSnapshot download = downloads.get(task.chartId);
            if (download != null) {
                DownloadProgress progress = download.progress();
                task.state = progress.state();
                task.bytes = progress.bytes();
                task.total = progress.total().orElse(-1);
                task.error = download.error().orElse("");
            } else if (task.state != DownloadState.DONE && task.state != DownloadState.FAILED
                    && task.state != DownloadState.CANCELLED) {
                try {
                    if (MainLoader.getBackbeatIntegration().hasChart(task.chartId)) {
                        task.state = DownloadState.DONE;
                    }
                } catch (RuntimeException ignored) {
                    // 保持当前状态，下一轮再查
                }
            }

            if (task.state == DownloadState.DONE) {
                if (!task.syncStarted) {
                    task.syncStarted = true;
                    startSync(task);
                }
            } else if (task.state == DownloadState.FAILED && !task.errorNotified) {
                notifyError(task);
            }
        }
    }

    public static void retry(String chartId) {
        Task task;
        synchronized (tasks) {
            task = tasks.get(chartId);
            if (task == null) {
                return;
            }
            task.state = DownloadState.QUEUED;
            task.bytes = 0;
            task.total = -1;
            task.error = "";
            task.synced = false;
            task.syncFailed = false;
            task.syncStarted = false;
            task.errorNotified = false;

            try {
                BackbeatIntegration integration = MainLoader.getBackbeatIntegration();
                integration.addServer(task.controller.getConfig().getBackbeatServerUrl());
                if (integration.hasChart(chartId)) {
                    task.state = DownloadState.DONE;
                } else {
                    integration.downloadChart(chartId);
                }
            } catch (RuntimeException error) {
                task.state = DownloadState.FAILED;
                task.error = message(error);
                notifyError(task);
            }
        }
    }

    public static void clearFinished() {
        synchronized (tasks) {
            tasks.values().removeIf(task ->
                    (task.state == DownloadState.DONE && task.synced)
                            || task.state == DownloadState.FAILED
                            || task.state == DownloadState.CANCELLED);
        }
    }

    private static void startSync(Task task) {
        Thread thread = new Thread(() -> {
            boolean success = false;
            try {
                success = MainLoader.pullFromBackbeat(task.controller.getInfoDatabase());
            } catch (RuntimeException error) {
                logger.warn("Backbeat song database sync failed: {}", message(error));
            }
            boolean result = success;
            Runnable finish = () -> {
                task.synced = result;
                task.syncFailed = !result;
                if (result) {
                    ImGuiNotify.success("Backbeat: " + task.title + " is ready. Press play again.");
                    if (task.onComplete != null) {
                        task.onComplete.run();
                    }
                } else {
                    ImGuiNotify.error("Backbeat: downloaded, but the song database sync failed.");
                }
            };
            if (Gdx.app != null) {
                Gdx.app.postRunnable(finish);
            } else {
                finish.run();
            }
        }, "backbeat-download-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private static void notifyError(Task task) {
        task.errorNotified = true;
        String detail = task.error == null || task.error.isBlank() ? task.title : task.error;
        ImGuiNotify.error("Backbeat download failed: " + detail);
    }

    private static String chartId(SongData song) {
        if (song == null) {
            return null;
        }
        String sha256 = song.getSha256();
        if (sha256 != null && !sha256.isBlank()) {
            return "sha256/" + sha256.trim();
        }
        String md5 = song.getMd5();
        if (md5 != null && !md5.isBlank()) {
            return "md5/" + md5.trim();
        }
        return null;
    }

    private static String title(SongData song) {
        if (song == null) {
            return "unknown";
        }
        String title = song.getFullTitle();
        if (title == null || title.isBlank()) {
            title = song.getTitle();
        }
        return title == null || title.isBlank() ? "unknown" : title;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
