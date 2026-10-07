package bms.player.beatoraja.modmenu;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

public class GaugeTrainerMenu {
	private static ImBoolean OVERRIDE_CHART_GAUGE = new ImBoolean(false);
	private static ImInt OVERRIDE_GAUGE_TYPE = new ImInt(GaugeTrainer.IIDX_EASY);

	public static void show(ImBoolean showGaugeTrainer) {
		float relativeX = windowWidth * 0.455f;
		float relativeY = windowHeight * 0.32f;
		ImGui.setWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

		if (ImGui.begin("Gauge Trainer", showGaugeTrainer, ImGuiWindowFlags.AlwaysAutoResize)) {
			if (ImGui.checkbox("Override chart's gauge", OVERRIDE_CHART_GAUGE)) {
				GaugeTrainer.setActive(OVERRIDE_CHART_GAUGE.get());
			}
			if (ImGui.combo("gauge", OVERRIDE_GAUGE_TYPE, GaugeTrainer.GAUGE_OPTIONS)) {
				GaugeTrainer.setGaugeType(OVERRIDE_GAUGE_TYPE.get());
			}
			ImGui.textDisabled("Takes effect on the next play.");
			ImGui.end();
		}
	}
}
