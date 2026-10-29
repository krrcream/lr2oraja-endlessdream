package bms.player.beatoraja.modmenu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;

/**
 * Chart Radar 的设置与持久化。
 *
 * <p>存成游戏目录下的 notesradar.json（与 ImGui 的 layout.ini 同目录），不写 PlayerConfig，
 * 因为后者是 LDJ 侧与 dev 侧共享的文件，加字段会带进合并冲突。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarSettings {

	/** 配置文件名，相对进程工作目录（发行版即游戏根目录） */
	private static final String FILE_NAME = "notesradar.json";

	/** 写盘限流间隔（毫秒） */
	private static final long SAVE_INTERVAL_MILLIS = 500;

	/**
	 * 设置内容。位置与大小都用窗口的百分比，窗口拉伸或换分辨率都不会跑位。
	 */
	public static final class Data {

		public boolean enabled = true;
		public float sizePercent = 20.0f;
		public boolean draggable = false;
		public boolean showValues = true;
		public float backgroundAlpha = 0.55f;
		public float selectX = 50.0f;
		public float selectY = 50.0f;
		public float playX = 50.0f;
		public float playY = 50.0f;
		public float resultX = 50.0f;
		public float resultY = 50.0f;
	}

	private static final Json json = new Json();

	private static Data data;
	private static boolean dirty;
	private static long lastSave;

	private ChartRadarSettings() {
	}

	public static Data get() {
		if (data == null) {
			data = load();
		}
		return data;
	}

	/** 取某个画面的坐标数组，槽位与 {@link ChartRadarOverlay} 的约定一致 */
	public static float[] position(int slot) {
		final Data settings = get();
		switch (slot) {
		case ChartRadarOverlay.SLOT_PLAY:
			return new float[] { settings.playX, settings.playY };
		case ChartRadarOverlay.SLOT_RESULT:
			return new float[] { settings.resultX, settings.resultY };
		default:
			return new float[] { settings.selectX, settings.selectY };
		}
	}

	/** 把某个画面的坐标写回设置 */
	public static void setPosition(int slot, float x, float y) {
		final Data settings = get();
		switch (slot) {
		case ChartRadarOverlay.SLOT_PLAY:
			settings.playX = x;
			settings.playY = y;
			break;
		case ChartRadarOverlay.SLOT_RESULT:
			settings.resultX = x;
			settings.resultY = y;
			break;
		default:
			settings.selectX = x;
			settings.selectY = y;
			break;
		}
		markDirty();
	}

	/** 标记有改动，实际写盘由 {@link #flush()} 限流完成 */
	public static void markDirty() {
		dirty = true;
	}

	/** 每帧调用：最多每 500ms 落盘一次，避免拖滑条时连续写文件 */
	public static void flush() {
		if (!dirty) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastSave < SAVE_INTERVAL_MILLIS) {
			return;
		}
		if (write()) {
			dirty = false;
			lastSave = now;
		}
	}

	private static Data load() {
		Data loaded = null;
		try {
			final FileHandle file = Gdx.files.local(FILE_NAME);
			if (file.exists()) {
				json.setUsePrototypes(false);
				loaded = json.fromJson(Data.class, file.readString("UTF-8"));
			}
		} catch (RuntimeException e) {
			System.err.println("[chart-radar] failed to read " + FILE_NAME + ": " + e.getMessage());
		}
		final Data settings = loaded != null ? loaded : new Data();
		// 文件可能被手改坏，读进来先夹到合法区间
		settings.sizePercent = clamp(settings.sizePercent, 5.0f, 60.0f);
		settings.backgroundAlpha = clamp(settings.backgroundAlpha, 0.0f, 1.0f);
		settings.selectX = clamp(settings.selectX, 0.0f, 100.0f);
		settings.selectY = clamp(settings.selectY, 0.0f, 100.0f);
		settings.playX = clamp(settings.playX, 0.0f, 100.0f);
		settings.playY = clamp(settings.playY, 0.0f, 100.0f);
		settings.resultX = clamp(settings.resultX, 0.0f, 100.0f);
		settings.resultY = clamp(settings.resultY, 0.0f, 100.0f);
		return settings;
	}

	private static boolean write() {
		try {
			json.setUsePrototypes(false);
			Gdx.files.local(FILE_NAME).writeString(json.prettyPrint(get()), false, "UTF-8");
			return true;
		} catch (RuntimeException e) {
			System.err.println("[chart-radar] failed to write " + FILE_NAME + ": " + e.getMessage());
			return false;
		}
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
