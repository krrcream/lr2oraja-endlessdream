package bms.player.beatoraja.modmenu;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.song.SongData;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;

/**
 * Chart Radar 的常驻覆盖窗口：勾选后一直在画面上，按当前画面切换到对应的坐标。
 *
 * <p>选曲与结算可交互（齿轮、拖动），游玩与加载画面强制鼠标穿透，因为游玩时拖动会移动鼠标准星、
 * 开着鼠标皿就有打皿风险。课程结算与各类设置画面不显示。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarOverlay {

	/** 三个画面槽位 */
	public static final int SLOT_SELECT = 0;
	public static final int SLOT_PLAY = 1;
	public static final int SLOT_RESULT = 2;

	private static final String WINDOW_LABEL = "##ChartRadar";

	private static final int BASE_FLAGS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize
			| ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoScrollbar
			| ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoFocusOnAppearing
			| ImGuiWindowFlags.NoBringToFrontOnFocus;

	/** 上一帧的画面类型，用来判断是否需要把窗口拉回该画面的坐标 */
	private static MainState.MainStateType lastType;

	private ChartRadarOverlay() {
	}

	public static void render(MainController main) {
		if (main == null) {
			return;
		}
		final ChartRadarSettings.Data settings = ChartRadarSettings.get();
		if (!settings.enabled) {
			lastType = null;
			return;
		}
		final MainState state = main.getCurrentState();
		if (state == null) {
			lastType = null;
			return;
		}
		final MainState.MainStateType type = MainController.getStateType(state);
		final int slot;
		final boolean interactive;
		if (type == MainState.MainStateType.MUSICSELECT) {
			slot = SLOT_SELECT;
			interactive = true;
		} else if (type == MainState.MainStateType.RESULT) {
			slot = SLOT_RESULT;
			interactive = true;
		} else if (type == MainState.MainStateType.PLAY || type == MainState.MainStateType.DECIDE) {
			// 加载画面沿用游玩坐标
			slot = SLOT_PLAY;
			interactive = false;
		} else {
			lastType = type;
			return;
		}

		final boolean screenChanged = lastType != type;
		lastType = type;

		final float[] position = ChartRadarSettings.position(slot);
		// 比标称尺寸多出一个字的宽高，配合绘制侧的边距，让标签离窗口边缘有余量
		final float size = Math.max(64.0f, ImGuiRenderer.windowHeight * settings.sizePercent / 100.0f)
				+ ChartRadarChart.extraMargin();
		final float windowX = ImGuiRenderer.windowWidth * position[0] / 100.0f - size / 2.0f;
		final float windowY = ImGuiRenderer.windowHeight * position[1] / 100.0f - size / 2.0f;

		final boolean draggable = interactive && settings.draggable;
		int flags = BASE_FLAGS;
		if (!interactive) {
			flags |= ImGuiWindowFlags.NoInputs;
		} else if (!draggable) {
			flags |= ImGuiWindowFlags.NoMove;
		}

		ImGui.setNextWindowSize(size, size, ImGuiCond.Always);
		if (!draggable || screenChanged) {
			// 换画面时把窗口拉回该画面对应的坐标；拖动过程中不强行纠正
			ImGui.setNextWindowPos(windowX, windowY, ImGuiCond.Always);
		}

		final int panelColor = ImGui.getColorU32(0.05f, 0.06f, 0.09f, settings.backgroundAlpha);
		if (ImGui.begin(WINDOW_LABEL, flags)) {
			final ImVec2 windowPos = ImGui.getWindowPos();
			ImGui.getWindowDrawList().addRectFilled(windowPos.x, windowPos.y, windowPos.x + size, windowPos.y + size,
					panelColor, 8.0f);

			final SongData song = state.resource.getSongdata();
			// 半径交给绘制侧按标签尺寸反算，避免文字越出窗口
			ChartRadarChart.draw(windowPos.x + size / 2.0f, windowPos.y + size / 2.0f, size,
					ChartRadarModelCache.get(song), settings.showValues);

			if (interactive) {
				final float buttonSize = ImGui.getFontSize() * 1.5f;
				ImGui.setCursorPos(size - buttonSize - 6.0f, 6.0f);
				if (ImGui.button(FontAwesomeIcons.Cog, buttonSize, buttonSize)) {
					ImGuiRenderer.setChartRadarSettingsVisible(true);
				}
			}
			if (draggable) {
				saveDraggedPosition(slot, windowPos, size, position);
			}
		}
		ImGui.end();
	}

	/** 把窗口中心换算成窗口百分比存回去，写盘限流由 ChartRadarSettings 负责 */
	private static void saveDraggedPosition(int slot, ImVec2 windowPos, float size, float[] stored) {
		final float centerX = (windowPos.x + size / 2.0f) / ImGuiRenderer.windowWidth * 100.0f;
		final float centerY = (windowPos.y + size / 2.0f) / ImGuiRenderer.windowHeight * 100.0f;
		if (Math.abs(centerX - stored[0]) > 0.05f || Math.abs(centerY - stored[1]) > 0.05f) {
			ChartRadarSettings.setPosition(slot, centerX, centerY);
		}
	}
}
