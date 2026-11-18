package bms.player.beatoraja.modmenu;

import bms.tool.mdprocessor.DownloadTask;
import bms.player.beatoraja.modmenu.DownloadTaskState;
import bms.tool.mdprocessor.HttpDownloadProcessor;
import ac.backbeat.sdk.DownloadState;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import java.util.List;
import java.util.Map;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

public class DownloadTaskMenu {
    public static final int MAXIMUM_TASK_NAME_LENGTH = 10;

    private static HttpDownloadProcessor processor;

    public static void setProcessor(HttpDownloadProcessor processor) {
        DownloadTaskMenu.processor = processor;
    }

    public static void show(ImBoolean showDownloadTasksWindow) {
        float relativeX = windowWidth * 0.455f;
        float relativeY = windowHeight * 0.04f;
        ImGui.setNextWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

        if (ImGui.begin("Download Tasks", showDownloadTasksWindow, ImGuiWindowFlags.AlwaysAutoResize)) {
            Map<Integer, DownloadTask> running = DownloadTaskState.runningDownloadTasks;
            Map<Integer, DownloadTask> expired = DownloadTaskState.expiredTasks;
            List<BackbeatDownloadState.Task> backbeatTasks = BackbeatDownloadState.tasks();
            if (running.isEmpty() && expired.isEmpty() && backbeatTasks.isEmpty()) {
                ImGui.text("No Download Task. Try selecting missing bms to submit new task!");
            } else {
                if (ImGui.beginTabBar("DownloadTasksTabBar")) {
                    if (ImGui.beginTabItem("Running")) {
                        renderTaskTable(running.values().stream().toList());
                        ImGui.endTabItem();
                    }
                    if (ImGui.beginTabItem("Expired")) {
                        renderTaskTable(expired.values().stream().toList());
                        ImGui.endTabItem();
                    }
                    if (ImGui.beginTabItem("Backbeat")) {
                        renderBackbeatTable(backbeatTasks);
                        ImGui.endTabItem();
                    }

                    ImGui.endTabBar();
                }
            }
        }
        ImGui.end();
    }

    private static void renderBackbeatTable(List<BackbeatDownloadState.Task> tasks) {
        if (ImGui.beginTable("BackbeatDownloadTaskTable", 4, ImGuiTableFlags.Borders | ImGuiTableFlags.ScrollY, 0, ImGui.getTextLineHeight() * 12)) {
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableSetupColumn("Task");
            ImGui.tableSetupColumn("State");
            ImGui.tableSetupColumn("Progress");
            ImGui.tableSetupColumn("Op");
            ImGui.tableHeadersRow();
            for (BackbeatDownloadState.Task task : tasks) {
                ImGui.tableNextRow();
                ImGui.pushID(task.chartId);

                ImGui.tableSetColumnIndex(0);
                ImGui.text(task.title);

                ImGui.tableSetColumnIndex(1);
                ImGui.text(task.state.name());

                ImGui.tableSetColumnIndex(2);
                if (task.error != null && !task.error.isEmpty()) {
                    ImGui.textColored(ImColor.rgb(255, 0, 0), task.error);
                } else if (task.total > 0) {
                    int percent = (int) Math.min(100, task.bytes * 100.0 / task.total);
                    ImGui.text(String.format("%s/%s (%d%%)", humanizeFileSize(task.bytes),
                            humanizeFileSize(task.total), percent));
                } else {
                    ImGui.text(humanizeFileSize(task.bytes));
                }

                ImGui.tableSetColumnIndex(3);
                if (task.state == DownloadState.FAILED || task.state == DownloadState.CANCELLED) {
                    if (ImGui.button("Retry")) {
                        BackbeatDownloadState.retry(task.chartId);
                    }
                } else if (task.synced) {
                    ImGui.textDisabled("Ready");
                }

                ImGui.popID();
            }
            ImGui.endTable();
        }
        if (ImGui.button("Clear finished")) {
            BackbeatDownloadState.clearFinished();
        }
    }

    private static void renderTaskTable(List<DownloadTask> tasks) {
        if (ImGui.beginTable("DownloadTaskTable", 3, ImGuiTableFlags.Borders | ImGuiTableFlags.ScrollY, 0, ImGui.getTextLineHeight() * 20)) {
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableSetupColumn("Task");
            ImGui.tableSetupColumn("Progress");
            ImGui.tableSetupColumn("Op");
            ImGui.tableHeadersRow();
            for (DownloadTask task : tasks) {
                ImGui.tableNextRow();
                ImGui.pushID(task.getId());

                ImGui.tableSetColumnIndex(0);
                String taskName = task.getName().substring(0, Math.min(task.getName().length(), MAXIMUM_TASK_NAME_LENGTH));
                ImGui.text(String.format("%s (%s)", taskName, task.getDownloadTaskStatus().getName()));

                ImGui.tableSetColumnIndex(1);
                String errorMessage = task.getErrorMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    ImGui.text(String.format("%s/%s", humanizeFileSize(task.getDownloadSize()), humanizeFileSize(task.getContentLength())));
                } else {
                    ImGui.textColored(ImColor.rgb(255, 0, 0), errorMessage);
                }

                ImGui.tableSetColumnIndex(2);
                if (task.getDownloadTaskStatus() == DownloadTask.DownloadTaskStatus.Error) {
                    if (ImGui.button("Retry") && processor != null) {
                        processor.retryDownloadTask(task);
                    }
                }

                ImGui.popID();
            }

            ImGui.endTable();
        }
    }

    public static String humanizeFileSize(long bytes) {
        int thresh = 1000;
        if (Math.abs(bytes) < thresh) {
            return String.format("%d B", bytes);
        }

        double result = bytes;
        final String[] units = new String[]{"KB", "MB", "GB", "TB"};
        int u = -1, r = 100;

        do {
            result /= thresh;
            ++u;
        } while (Math.round(Math.abs(result) * r) / r >= thresh && u < units.length - 1);

        return String.format("%.1f %s", result, units[u]);
    }
}
