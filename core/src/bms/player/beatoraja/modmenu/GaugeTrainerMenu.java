package bms.player.beatoraja.modmenu;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

public class GaugeTrainerMenu {

	/**
	 * 复选框的显示文字，下标与 GrooveGauge 的槽位编号一致（0 = A-EASY … 4 = EX-HARD）。
	 */
	private static final String[] GAUGE_LABELS = new String[]{
			"IIDX A-EASY", "IIDX EASY", "IIDX NORMAL", "IIDX HARD", "IIDX EX-HARD"
	};

	/**
	 * 各 IIDX 血条的复选框，下标含义同 {@link #GAUGE_LABELS}。
	 */
	private static final ImBoolean[] OVERRIDE_GAUGE = new ImBoolean[GaugeTrainer.GAUGE_COUNT];

	static {
		for (int i = 0; i < OVERRIDE_GAUGE.length; i++) {
			OVERRIDE_GAUGE[i] = new ImBoolean(false);
		}
	}

	public static void show(ImBoolean showGaugeTrainer) {
		float relativeX = windowWidth * 0.455f;
		float relativeY = windowHeight * 0.32f;
		ImGui.setWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

		if (ImGui.begin("Gauge Trainer", showGaugeTrainer, ImGuiWindowFlags.AlwaysAutoResize)) {
			ImGui.textDisabled("If enabled, the result will be saved as an A-Clear record");
			for (int i = 0; i < OVERRIDE_GAUGE.length; i++) {
				if (ImGui.checkbox(GAUGE_LABELS[i], OVERRIDE_GAUGE[i])) {
					GaugeTrainer.setEnabled(i, OVERRIDE_GAUGE[i].get());
				}
			}
			ImGui.textDisabled("Takes effect on the next play.");
			ImGui.end();
		}
	}
}
