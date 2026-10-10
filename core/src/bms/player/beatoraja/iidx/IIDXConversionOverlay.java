package bms.player.beatoraja.iidx;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowHeight;
import static bms.player.beatoraja.modmenu.ImGuiRenderer.windowWidth;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;

/**
 * IIDX譜面の変換進捗を画面中央に表示するオーバーレイ。
 *
 * <p>
 * ImGuiのフレームは{@code MainController.render()}が{@code ImGuiRenderer}経由で
 * 管理しており、選曲状態({@code MusicSelector.render()})からは描画できない。
 * そのため進捗はこのクラスの静的フィールドに保持し、実際の描画は
 * {@code ImGuiRenderer.render()}から呼び出してもらう。
 * 変換スレッドから更新されるため、フィールドはvolatileとする。
 *
 * @author endlessdream
 */
public class IIDXConversionOverlay {

	/**
	 * オーバーレイの幅(px)
	 */
	private static final float WIDTH = 480.0f;

	private static volatile boolean visible = false;

	private static volatile String title = "";

	/**
	 * 進捗(0-100)。-1は非表示を表す
	 */
	private static volatile int progress = -1;

	private static volatile String stage = "";

	private IIDXConversionOverlay() {
	}

	/**
	 * 変換の開始時に進捗表示を出す(UIスレッド)
	 */
	public static void show(String songTitle) {
		title = songTitle != null ? songTitle : "";
		progress = 0;
		stage = "";
		visible = true;
	}

	/**
	 * 進捗を更新する(変換スレッドから呼ばれる)
	 */
	public static void update(int percent, String message) {
		progress = percent;
		stage = message != null ? message : "";
	}

	/**
	 * 進捗表示を消す(UIスレッド)
	 */
	public static void hide() {
		visible = false;
		progress = -1;
		stage = "";
	}

	/**
	 * オーバーレイを描画する。ImGuiのフレーム内(ImGuiRenderer.render)からのみ呼ぶこと
	 */
	public static void render() {
		if (!visible) {
			return;
		}
		final int percent = Math.max(0, Math.min(100, progress));
		final String label = title.isEmpty() ? "IIDX 変換" : title;

		ImGui.setNextWindowPos(windowWidth * 0.5f, windowHeight * 0.5f, ImGuiCond.Always, 0.5f, 0.5f);
		ImGui.setNextWindowBgAlpha(0.9f);
		ImGui.begin(label,
				ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize
						| ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoCollapse
						| ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoFocusOnAppearing
						| ImGuiWindowFlags.NoNav);
		ImGui.text("IIDX譜面を変換しています...");
		ImGui.progressBar(percent / 100.0f, new ImVec2(WIDTH, 0.0f), percent + "%");
		if (!stage.isEmpty()) {
			ImGui.text(stage);
		}
		ImGui.end();
	}
}
