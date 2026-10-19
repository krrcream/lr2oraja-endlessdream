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

    /** 转换方向：0 = SP→DP，1 = DP→SP。 */
    private static int DIRECTION = 0;

    /** DP→SP 是否处理 jack（默认开启）。 */
    private static ImBoolean PROCESS_JACK = new ImBoolean(true);

    public static void show(ImBoolean showSpdpConvert) {
        float relativeX = windowWidth * 0.48f;
        float relativeY = windowHeight * 0.04f;
        ImGui.setNextWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

        if (ImGui.begin("SPDP Convert", showSpdpConvert, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Converts between single play and double play");
            ImGui.text("(SP 7K+scratch  <->  DP 14K+2 scratches)");
            ImGui.spacing();
            ImGui.checkbox("Enabled", ENABLED);
            ImGui.sameLine();
            helpMarker("When enabled, SP charts load as DP. Result shows the clear lamp "
                    + "but is not scored and not submitted to IR.");
            ImGui.spacing();
            if (ImGui.radioButton("SPtoDP", DIRECTION == 0)) {
                DIRECTION = 0;
            }
            if (ImGui.radioButton("DPtoSP", DIRECTION == 1)) {
                DIRECTION = 1;
            }
            ImGui.checkbox("Process Jack", PROCESS_JACK);
        }
        ImGui.end();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    /** 是否 DP→SP 方向（否则 SP→DP）。 */
    public static boolean isDPSP() {
        return DIRECTION == 1;
    }

    /** 是否处理 jack（仅 DP→SP 生效）。 */
    public static boolean isProcessJack() {
        return PROCESS_JACK.get();
    }
}
