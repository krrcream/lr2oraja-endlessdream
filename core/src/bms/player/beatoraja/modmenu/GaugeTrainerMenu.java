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
	 * 单选按钮的显示文字，目前只有下标 0 = IIDX type。
	 */
	private static final String[] GAUGE_TYPE_LABELS = new String[]{
			"IIDX type"
	};

	/**
	 * IIDX type 在 {@link #GAUGE_TYPE_LABELS} 中的下标，选中它等同于把 5 个 IIDX 血条全部打开。
	 */
	private static final int GAUGE_TYPE_INDEX_IIDX = 0;

	/**
	 * 覆盖血条的总开关。不打勾时下方选的类型一律不生效。
	 */
	private static final ImBoolean OVERRIDE_GAUGE = new ImBoolean(false);

	/**
	 * 当前选中的血条类型。默认停在 IIDX type，勾上总开关就立刻生效。
	 */
	private static final ImInt OVERRIDE_GAUGE_TYPE = new ImInt(GAUGE_TYPE_INDEX_IIDX);

	public static void show(ImBoolean showGaugeTrainer) {
		float relativeX = windowWidth * 0.455f;
		float relativeY = windowHeight * 0.32f;
		ImGui.setWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

		if (ImGui.begin("Gauge Trainer", showGaugeTrainer, ImGuiWindowFlags.AlwaysAutoResize)) {
			ImGui.textDisabled("If any gauge is enabled, the result will not be recorded");
			if (ImGui.checkbox("Override gauge", OVERRIDE_GAUGE)) {
				applyGaugeType(selectedGaugeType());
			}
			// 总开关没打勾时把类型选择灰掉：这时点它不会有效果，灰掉才不会让人以为已经生效。
			ImGui.beginDisabled(!OVERRIDE_GAUGE.get());
			for (int i = 0; i < GAUGE_TYPE_LABELS.length; i++) {
				if (ImGui.radioButton(GAUGE_TYPE_LABELS[i], OVERRIDE_GAUGE_TYPE, i)) {
					applyGaugeType(selectedGaugeType());
				}
			}
			ImGui.endDisabled();
			ImGui.textDisabled("Takes effect on the next play.");
			ImGui.end();
		}
	}

	/**
	 * 总开关没打勾时返回 -1，表示不施加任何覆盖。
	 */
	private static int selectedGaugeType() {
		return OVERRIDE_GAUGE.get() ? OVERRIDE_GAUGE_TYPE.get() : -1;
	}

	/**
	 * IIDX type 等价于 5 个 IIDX 血条全部启用；type 为 -1（总开关没打勾）时不施加任何覆盖。
	 */
	private static void applyGaugeType(int type) {
		boolean iidx = type == GAUGE_TYPE_INDEX_IIDX;
		for (int slot = 0; slot < GaugeTrainer.GAUGE_COUNT; slot++) {
			GaugeTrainer.setEnabled(slot, iidx);
		}
	}
}
