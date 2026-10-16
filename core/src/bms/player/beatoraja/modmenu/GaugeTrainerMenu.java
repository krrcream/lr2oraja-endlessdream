package bms.player.beatoraja.modmenu;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

public class GaugeTrainerMenu {

	/**
	 * 单选按钮的显示文字，下标 0 = IIDX type、1 = Beatoraja type。
	 */
	private static final String[] GAUGE_TYPE_LABELS = new String[]{
			"IIDX type", "Beatoraja type"
	};

	/**
	 * IIDX type 在 {@link #GAUGE_TYPE_LABELS} 中的下标，选中它等同于把 5 个 IIDX 血条全部打开。
	 */
	private static final int GAUGE_TYPE_INDEX_IIDX = 0;

	/**
	 * 当前选中的血条类型。-1 表示都不选中，此时不施加任何覆盖，成绩照常记录。
	 */
	private static final ImInt OVERRIDE_GAUGE_TYPE = new ImInt(-1);

	public static void show(ImBoolean showGaugeTrainer) {
		float relativeX = windowWidth * 0.455f;
		float relativeY = windowHeight * 0.32f;
		ImGui.setWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

		if (ImGui.begin("Gauge Trainer", showGaugeTrainer, ImGuiWindowFlags.AlwaysAutoResize)) {
			ImGui.textDisabled("If any gauge is enabled, the result will not be recorded");
			ImGui.textDisabled("In LDJ, the result is still recorded when using the IIDX settings.");
			for (int i = 0; i < GAUGE_TYPE_LABELS.length; i++) {
				if (ImGui.radioButton(GAUGE_TYPE_LABELS[i], OVERRIDE_GAUGE_TYPE, i)) {
					applyGaugeType(OVERRIDE_GAUGE_TYPE.get());
				}
			}
			ImGui.textDisabled("Takes effect on the next play.");
			ImGui.end();
		}
	}

	/**
	 * IIDX type 等价于 5 个 IIDX 血条全部启用；Beatoraja type 暂未实现，含义是不做任何覆盖。
	 */
	private static void applyGaugeType(int type) {
		boolean iidx = type == GAUGE_TYPE_INDEX_IIDX;
		for (int slot = 0; slot < GaugeTrainer.GAUGE_COUNT; slot++) {
			GaugeTrainer.setEnabled(slot, iidx);
		}
	}
}
