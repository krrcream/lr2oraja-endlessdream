package bms.player.beatoraja.ldj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import bms.model.Mode;
import bms.player.beatoraja.ldj.LDJChartRef.Difficulty;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;

/**
 * 读取 iidx2bms 的 {@code music_data/music_data.json}，组装出排列在虚拟LDJ文件夹中的谱面
 *
 * <p>这里创建的 {@link SongData} 不持有实际文件，路径是
 * {@code iidx://<songId>/<difficulty>} 这样的虚拟路径，实际BMS会在选曲时由
 * {@link LDJConversionService} 转换后的结果替换</p>
 *
 * <p>转换前无法得知将要生成的BMS的哈希，因此先写入临时的 md5/sha256
 * 这里只要求“非空”与“每个谱面唯一”，转换成功
 * 时会被真实哈希覆盖，在仍是临时哈希的期间，选曲列表中不会出现通关灯
 * (v1 的已知限制)</p>
 *
 * @author endlessdream
 */
public class LDJSongProvider {

	private static final Logger logger = LoggerFactory.getLogger(LDJSongProvider.class);

	/** 存放 music_data.json 的目录名 */
	public static final String MUSIC_DATA_DIRECTORY = "music_data";

	/** 曲目元数据的文件名 */
	public static final String MUSIC_DATA_FILE = "music_data.json";

	/** 存放等级的键的后缀 (例: "SPA_level") */
	private static final String LEVEL_SUFFIX = "_level";

	private final String projectRoot;

	private final Path musicDataPath;

	/** 已读取的曲目元数据，由 {@link #load()} 设置 */
	private List<LDJChartMeta> charts = Collections.emptyList();

	private Map<String, LDJChartMeta> chartsById = Collections.emptyMap();

	private boolean loadAttempted;

	private boolean available;

	public LDJSongProvider(String projectRoot) {
		this.projectRoot = projectRoot == null ? "" : projectRoot.trim();
		this.musicDataPath = this.projectRoot.isEmpty() ? null
				: Paths.get(this.projectRoot).resolve(MUSIC_DATA_DIRECTORY).resolve(MUSIC_DATA_FILE);
	}

	public String getProjectRoot() {
		return projectRoot;
	}

	/**
	 * 所引用的 music_data.json 的路径，未设置项目根目录时为 null
	 */
	public Path getMusicDataPath() {
		return musicDataPath;
	}

	/**
	 * music_data.json 是否处于可读状态，不可读时不显示LDJ文件夹
	 */
	public boolean isAvailable() {
		load();
		return available;
	}

	/**
	 * 已读取的曲目元数据(只读)
	 */
	public List<LDJChartMeta> getCharts() {
		load();
		return Collections.unmodifiableList(charts);
	}

	/**
	 * 根据曲目ID取出元数据，找不到时返回 null
	 */
	public LDJChartMeta getChart(String songId) {
		if (songId == null) {
			return null;
		}
		load();
		return chartsById.get(songId.trim());
	}

	public LDJChartMeta getChart(LDJChartRef ref) {
		return ref == null ? null : getChart(ref.getSongId());
	}

	/**
	 * 枚举与指定模式对应的谱面
	 *
	 * <p>等级为 0 的难度视为未收录，予以排除</p>
	 *
	 * @param mode 选曲画面的模式，7K 时仅 SP，14K 时仅 DP
	 */
	public SongBar[] getSongBars(Mode mode) {
		return getSongBars(mode == Mode.BEAT_14K);
	}

	/**
	 * 直接指定是否枚举双人谱面(double play)的版本
	 */
	public SongBar[] getSongBars(boolean doublePlay) {
		load();
		List<SongBar> bars = new ArrayList<SongBar>();
		for (LDJChartMeta meta : charts) {
			for (Difficulty difficulty : Difficulty.values()) {
				if (difficulty.isDoublePlay() != doublePlay || !meta.hasChart(difficulty)) {
					continue;
				}
				bars.add(new SongBar(createSongData(meta, difficulty)));
			}
		}
		return bars.toArray(new SongBar[bars.size()]);
	}

	/**
	 * 创建转换前的临时 {@link SongData}
	 */
	public static SongData createSongData(LDJChartMeta meta, Difficulty difficulty) {
		SongData song = new SongData();
		String path = new LDJChartRef(meta.getSongId(), difficulty).getPath();
		song.setPath(path);
		song.setTitle(meta.getTitle());
		// getFullTitle() 在 subtitle 为 null 时会崩溃，因此显式填入空字符串
		song.setSubtitle("");
		song.setArtist(meta.getArtist() == null ? "" : meta.getArtist());
		song.setGenre(meta.getGenre() == null ? "" : meta.getGenre());
		song.setLevel(meta.getLevel(difficulty));
		song.setDifficulty(difficulty.getBmsDifficulty());
		song.setMode(difficulty.getMode().id);
		song.setMd5(placeholderHash(path, 32));
		String scoreKey = placeholderHash(path, 64);
		song.setSha256(scoreKey);
		// 为了让路径在转换时被改写为实际文件后仍能看出源自LDJ，在生成时就确定分数键
		song.setLDJScoreKey(scoreKey);
		// 保留 iidx:// 源路径，缓存被删后可据此重转
		song.setLDJPath(path);
		return song;
	}

	/**
	 * 委托给 {@link LDJChartRef#isLDJPath(String)}
	 */
	public static boolean isLDJPath(String path) {
		return LDJChartRef.isLDJPath(path);
	}

	/**
	 * 委托给 {@link LDJChartRef#parse(String)}
	 */
	public static LDJChartRef parsePath(String path) {
		return LDJChartRef.parse(path);
	}

	/**
	 * 由临时路径决定的临时哈希，每个谱面唯一，内容相同时始终为相同的值
	 */
	private static String placeholderHash(String path, int length) {
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte b : bytes) {
				builder.append(String.format("%02x", b & 0xFF));
			}
			return builder.substring(0, Math.min(length, builder.length()));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 必定存在，但万一出现异常时用非空的适当值代替
			return Integer.toHexString(path.hashCode());
		}
	}

	private synchronized void load() {
		if (loadAttempted) {
			return;
		}
		loadAttempted = true;

		if (musicDataPath == null || !Files.isRegularFile(musicDataPath)) {
			logger.info("LDJ music_data.json was not found: {}", musicDataPath);
			return;
		}

		List<LDJChartMeta> parsed = new ArrayList<LDJChartMeta>();
		Map<String, LDJChartMeta> byId = new HashMap<String, LDJChartMeta>();
		try {
			String json = new String(Files.readAllBytes(musicDataPath), StandardCharsets.UTF_8);
			JsonValue root = new JsonReader().parse(json);
			JsonValue data = root.get("data");
			if (data == null) {
				logger.error("{} has no 'data' array", musicDataPath);
				return;
			}
			for (JsonValue song = data.child; song != null; song = song.next) {
				LDJChartMeta meta = parseSong(song);
				if (meta != null) {
					parsed.add(meta);
					byId.put(meta.getSongId(), meta);
				}
			}
		} catch (IOException e) {
			logger.error("Failed to read {}: {}", musicDataPath, e.getMessage());
			return;
		} catch (RuntimeException e) {
			logger.error("Failed to parse {}: {}", musicDataPath, e.getMessage());
			return;
		}

		charts = parsed;
		chartsById = byId;
		available = true;
		logger.info("Loaded {} LDJ songs from {}", charts.size(), musicDataPath);
	}

	private static LDJChartMeta parseSong(JsonValue song) {
		String title = song.getString("title", "").trim();
		String songId = Integer.toString(intValue(song, "song_id"));
		if (title.isEmpty() || "0".equals(songId)) {
			return null;
		}
		return new LDJChartMeta(songId, title, song.getString("artist", ""), song.getString("genre", ""),
				intValue(song, "game_version"), intValue(song, "volume"),
				level(song, Difficulty.SPB), level(song, Difficulty.SPN), level(song, Difficulty.SPH),
				level(song, Difficulty.SPA), level(song, Difficulty.SPL), level(song, Difficulty.DPB),
				level(song, Difficulty.DPN), level(song, Difficulty.DPH), level(song, Difficulty.DPA),
				level(song, Difficulty.DPL));
	}

	/** 等级以 "SPA_level" 这样的大写难度记号+后缀的形式存放 */
	private static int level(JsonValue song, Difficulty difficulty) {
		return intValue(song, difficulty.name() + LEVEL_SUFFIX);
	}

	private static int intValue(JsonValue parent, String name) {
		JsonValue value = parent.get(name);
		if (value == null) {
			return 0;
		}
		try {
			return value.asInt();
		} catch (RuntimeException e) {
			try {
				return (int) value.asDouble();
			} catch (RuntimeException ignored) {
				return 0;
			}
		}
	}
}
