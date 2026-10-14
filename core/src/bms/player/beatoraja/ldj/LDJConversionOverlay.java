package bms.player.beatoraja.ldj;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;

/**
 * 在画面中央显示 LDJ 谱面转换进度的覆盖层
 *
 * <p>
 * ImGui 的帧由{@code MainController.render()}经{@code ImGuiRenderer}
 * 管理，因此无法从选曲状态({@code MusicSelector.render()})中进行绘制
 * 因此进度保存在本类的静态字段中，实际的绘制则
 * 交由{@code ImGuiRenderer.render()}调用
 * 由于会从转换线程更新，字段声明为 volatile
 *
 * @author endlessdream
 */
public class LDJConversionOverlay {

	/**
	 * 覆盖层的宽度(px)
	 */
	private static final float WIDTH = 480.0f;

	private static volatile boolean visible = false;

	private static volatile String title = "";

	/**
	 * 进度(0-100)，-1 表示不显示
	 */
	private static volatile int progress = -1;

	private static volatile String stage = "";

	private LDJConversionOverlay() {
	}

	/**
	 * 转换开始时显示进度(UI 线程)
	 */
	public static void show(String songTitle) {
		title = songTitle != null ? songTitle : "";
		progress = 0;
		stage = "";
		visible = true;
	}

	/**
	 * 更新进度(由转换线程调用)
	 */
	public static void update(int percent, String message) {
		progress = percent;
		stage = message != null ? message : "";
	}

	/**
	 * 隐藏进度显示(UI 线程)
	 */
	public static void hide() {
		visible = false;
		progress = -1;
		stage = "";
	}

	/**
	 * 绘制覆盖层，只能在 ImGui 的帧内(ImGuiRenderer.render)调用
	 */
	public static void render() {
		if (!visible) {
			return;
		}
		final int percent = Math.max(0, Math.min(100, progress));
		final String label = title.isEmpty() ? "LDJ 変換" : title;

		ImGui.setNextWindowPos(windowWidth * 0.5f, windowHeight * 0.5f, ImGuiCond.Always, 0.5f, 0.5f);
		ImGui.setNextWindowBgAlpha(0.9f);
		ImGui.begin(label,
				ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize
						| ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoCollapse
						| ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoFocusOnAppearing
						| ImGuiWindowFlags.NoNav);
		ImGui.text("LDJ譜面を変換しています...");
		ImGui.progressBar(percent / 100.0f, new ImVec2(WIDTH, 0.0f), percent + "%");
		if (!stage.isEmpty()) {
			ImGui.text(stage);
		}
		ImGui.end();
	}
}
