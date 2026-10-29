package bms.player.beatoraja.modmenu;

import bms.player.beatoraja.MainController;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.helpMarker;

/**
 * Chart Radar 的 F5 侧菜单：开关项与设置窗口。
 *
 * <p>设置窗口由 F5 菜单与雷达窗口右上角的齿轮共同打开，两边改的是同一份 {@link ChartRadarSettings}，
 * 所以刷新后按钮与滑条同步回填。</p>
 *
 * @author endlessdream
 */
public class ChartRadarMenu {

	/** 由 MainController 在 updateStateReferences 时注入 */
	private static MainController main;

	private static final ImBoolean ENABLED = new ImBoolean(true);
	private static final ImBoolean DRAGGABLE = new ImBoolean(false);
	private static final ImBoolean SHOW_VALUES = new ImBoolean(true);
	private static final float[] ALPHA = new float[1];
	private static final float[] SELECT = new float[2];
	private static final float[] PLAY = new float[2];
	private static final float[] RESULT = new float[2];
	private static final float[] SELECT_SIZE = new float[1];
	private static final float[] PLAY_SIZE = new float[1];
	private static final float[] RESULT_SIZE = new float[1];

	public static void init(MainController mainController) {
		main = mainController;
	}

	/** F5 菜单的第一项：开关。勾上时同时把设置窗口打开。 */
	public static void renderEnableCheckbox() {
		final ChartRadarSettings.Data settings = ChartRadarSettings.get();
		ENABLED.set(settings.enabled);
		if (ImGui.checkbox("Chart Radar", ENABLED)) {
			settings.enabled = ENABLED.get();
			ChartRadarSettings.markDirty();
			if (settings.enabled) {
				ImGuiRenderer.setChartRadarSettingsVisible(true);
			}
		}
		sameLineHelp("Six-axis chart (0-200) of the selected chart: NOTES / PEAK / SCRATCH / SO-FLAN / CHARGE / CHORD. "
				+ "The highest axis gets a gold star.");
	}

	/** 设置窗口内容 */
	public static void show(ImBoolean showFlag) {
		final ChartRadarSettings.Data settings = ChartRadarSettings.get();
		// 每帧先把设置同步进控件，再按控件改动写回
		ENABLED.set(settings.enabled);
		DRAGGABLE.set(settings.draggable);
		SHOW_VALUES.set(settings.showValues);
		ALPHA[0] = settings.backgroundAlpha;
		SELECT[0] = settings.selectX;
		SELECT[1] = settings.selectY;
		PLAY[0] = settings.playX;
		PLAY[1] = settings.playY;
		RESULT[0] = settings.resultX;
		RESULT[1] = settings.resultY;
		SELECT_SIZE[0] = settings.selectSize;
		PLAY_SIZE[0] = settings.playSize;
		RESULT_SIZE[0] = settings.resultSize;

		if (ImGui.begin("Chart Radar Settings", showFlag, ImGuiWindowFlags.AlwaysAutoResize)) {
			if (ImGui.checkbox("Enable", ENABLED)) {
				settings.enabled = ENABLED.get();
				ChartRadarSettings.markDirty();
			}
			if (ImGui.checkbox("Draggable", DRAGGABLE)) {
				settings.draggable = DRAGGABLE.get();
				ChartRadarSettings.markDirty();
			}
			sameLineHelp("When enabled, drag the radar window directly. The position is saved for the current screen. "
					+ "Dragging is disabled while playing.");
			if (ImGui.checkbox("Show values", SHOW_VALUES)) {
				settings.showValues = SHOW_VALUES.get();
				ChartRadarSettings.markDirty();
			}
			if (ImGui.sliderFloat("Background opacity", ALPHA, 0.0f, 1.0f)) {
				settings.backgroundAlpha = ALPHA[0];
				ChartRadarSettings.markDirty();
			}

			ImGui.separator();
			ImGui.text("Layout (position: percent of window, size: percent of window height)");
			if (ImGui.sliderFloat2("Select X/Y", SELECT, 0.0f, 100.0f)) {
				settings.selectX = SELECT[0];
				settings.selectY = SELECT[1];
				ChartRadarSettings.markDirty();
			}
			ImGui.sameLine();
			if (ImGui.sliderFloat("Size", SELECT_SIZE, 5.0f, 60.0f)) {
				ChartRadarSettings.setSize(ChartRadarOverlay.SLOT_SELECT, SELECT_SIZE[0]);
			}
			if (ImGui.sliderFloat2("Play X/Y", PLAY, 0.0f, 100.0f)) {
				settings.playX = PLAY[0];
				settings.playY = PLAY[1];
				ChartRadarSettings.markDirty();
			}
			ImGui.sameLine();
			if (ImGui.sliderFloat("Size", PLAY_SIZE, 5.0f, 60.0f)) {
				ChartRadarSettings.setSize(ChartRadarOverlay.SLOT_PLAY, PLAY_SIZE[0]);
			}
			if (ImGui.sliderFloat2("Result X/Y", RESULT, 0.0f, 100.0f)) {
				settings.resultX = RESULT[0];
				settings.resultY = RESULT[1];
				ChartRadarSettings.markDirty();
			}
			ImGui.sameLine();
			if (ImGui.sliderFloat("Size", RESULT_SIZE, 5.0f, 60.0f)) {
				ChartRadarSettings.setSize(ChartRadarOverlay.SLOT_RESULT, RESULT_SIZE[0]);
			}
			if (ImGui.button("Copy Select to Play")) {
				copyPosition(settings.selectX, settings.selectY, ChartRadarOverlay.SLOT_PLAY);
			}
			ImGui.sameLine();
			if (ImGui.button("Copy Select to Result")) {
				copyPosition(settings.selectX, settings.selectY, ChartRadarOverlay.SLOT_RESULT);
			}
		}
		ImGui.end();
	}

	/** 每帧调用：落盘限流 + 画常驻覆盖窗口 */
	public static void renderOverlay() {
		ChartRadarSettings.flush();
		ChartRadarOverlay.render(main);
	}

	private static void copyPosition(float x, float y, int slot) {
		ChartRadarSettings.setPosition(slot, x, y);
	}

	private static void sameLineHelp(String description) {
		ImGui.sameLine();
		helpMarker(description);
	}
}
