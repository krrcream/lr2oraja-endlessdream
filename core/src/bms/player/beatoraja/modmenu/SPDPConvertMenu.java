package bms.player.beatoraja.modmenu;

import bms.player.beatoraja.pattern.SPDPConvertModifier;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.helpMarker;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

/**
 * SP→DP 转换窗口。开启后，8-lane 的 SP 谱在读入时会被转换成 DP 谱游玩。
 */
public class SPDPConvertMenu {

    private static ImBoolean ENABLED = new ImBoolean(false);

    public static void show(ImBoolean showSpdpConvert) {
        float relativeX = windowWidth * 0.48f;
        float relativeY = windowHeight * 0.04f;
        ImGui.setNextWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

        if (ImGui.begin("SPDP Convert", showSpdpConvert, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Converts an 8-lane (7K+scratch) chart to double play");
            ImGui.text("(14 keys + 2 scratches) on load.");
            ImGui.spacing();
            ImGui.checkbox("Enabled", ENABLED);
            ImGui.sameLine();
            helpMarker("When enabled, SP charts load as DP. Result shows the clear lamp "
                    + "but is not scored and not submitted to IR.");
        }
        ImGui.end();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }
}
