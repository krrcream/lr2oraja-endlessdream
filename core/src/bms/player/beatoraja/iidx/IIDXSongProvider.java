package bms.player.beatoraja.iidx;

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
import bms.player.beatoraja.iidx.IIDXChartRef.Difficulty;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;

/**
 * iidx2bmsの {@code music_data/music_data.json} を読み、仮想IIDXフォルダに並べる譜面を組み立てる。
 *
 * <p>ここで作る {@link SongData} は実ファイルを持たない。パスは
 * {@code iidx://<songId>/<difficulty>} という仮想パスで、実際のBMSへは選曲時に
 * {@link IIDXConversionService} が変換した結果で置き換わる。</p>
 *
 * <p>変換前は生成されるBMSのハッシュが分からないため、仮のmd5/sha256を入れておく。
 * これは「空でないこと」と「譜面ごとに一意であること」だけが要件で、変換が成功した
 * 時点で実ハッシュに上書きされる。仮ハッシュの間は選曲リストにランプが出ない
 * (v1の既知の制限)。</p>
 *
 * @author endlessdream
 */
public class IIDXSongProvider {

	private static final Logger logger = LoggerFactory.getLogger(IIDXSongProvider.class);

	/** music_data.json を置くディレクトリ名 */
	public static final String MUSIC_DATA_DIRECTORY = "music_data";

	/** 曲メタデータのファイル名 */
	public static final String MUSIC_DATA_FILE = "music_data.json";

	/** レベルが格納されているキーの接尾辞 (例: "SPA_level") */
	private static final String LEVEL_SUFFIX = "_level";

	private final String projectRoot;

	private final Path musicDataPath;

	/** 読み込み済みの曲メタデータ。{@link #load()} が設定する */
	private List<IIDXChartMeta> charts = Collections.emptyList();

	private Map<String, IIDXChartMeta> chartsById = Collections.emptyMap();

	private boolean loadAttempted;

	private boolean available;

	public IIDXSongProvider(String projectRoot) {
		this.projectRoot = projectRoot == null ? "" : projectRoot.trim();
		this.musicDataPath = this.projectRoot.isEmpty() ? null
				: Paths.get(this.projectRoot).resolve(MUSIC_DATA_DIRECTORY).resolve(MUSIC_DATA_FILE);
	}

	public String getProjectRoot() {
		return projectRoot;
	}

	/**
	 * 参照している music_data.json のパス。プロジェクトルート未設定ならnull
	 */
	public Path getMusicDataPath() {
		return musicDataPath;
	}

	/**
	 * music_data.json が読める状態にあるか。読めない場合はIIDXフォルダを出さない
	 */
	public boolean isAvailable() {
		load();
		return available;
	}

	/**
	 * 読み込んだ曲メタデータ(読み取り専用)
	 */
	public List<IIDXChartMeta> getCharts() {
		load();
		return Collections.unmodifiableList(charts);
	}

	/**
	 * 曲IDからメタデータを引く。見つからなければnull
	 */
	public IIDXChartMeta getChart(String songId) {
		if (songId == null) {
			return null;
		}
		load();
		return chartsById.get(songId.trim());
	}

	public IIDXChartMeta getChart(IIDXChartRef ref) {
		return ref == null ? null : getChart(ref.getSongId());
	}

	/**
	 * 指定したモードに対応する譜面を列挙する。
	 *
	 * <p>レベル0の難易度は収録されていないものとして除く。</p>
	 *
	 * @param mode 選曲画面のモード。7KならSP、14KならDPのみ
	 */
	public SongBar[] getSongBars(Mode mode) {
		return getSongBars(mode == Mode.BEAT_14K);
	}

	/**
	 * ダブルプレイ譜面を列挙するかどうかを直接指定する版
	 */
	public SongBar[] getSongBars(boolean doublePlay) {
		load();
		List<SongBar> bars = new ArrayList<SongBar>();
		for (IIDXChartMeta meta : charts) {
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
	 * 変換前の仮の {@link SongData} を作る
	 */
	public static SongData createSongData(IIDXChartMeta meta, Difficulty difficulty) {
		SongData song = new SongData();
		String path = new IIDXChartRef(meta.getSongId(), difficulty).getPath();
		song.setPath(path);
		song.setTitle(meta.getTitle());
		// getFullTitle() は subtitle が null だと落ちるため明示的に空を入れる
		song.setSubtitle("");
		song.setArtist(meta.getArtist() == null ? "" : meta.getArtist());
		song.setGenre(meta.getGenre() == null ? "" : meta.getGenre());
		song.setLevel(meta.getLevel(difficulty));
		song.setDifficulty(difficulty.getBmsDifficulty());
		song.setMode(difficulty.getMode().id);
		song.setMd5(placeholderHash(path, 32));
		song.setSha256(placeholderHash(path, 64));
		return song;
	}

	/**
	 * {@link IIDXChartRef#isIIDXPath(String)} への委譲
	 */
	public static boolean isIIDXPath(String path) {
		return IIDXChartRef.isIIDXPath(path);
	}

	/**
	 * {@link IIDXChartRef#parse(String)} への委譲
	 */
	public static IIDXChartRef parsePath(String path) {
		return IIDXChartRef.parse(path);
	}

	/**
	 * 仮パスから決まる仮ハッシュ。譜面ごとに一意で、内容が同じなら常に同じ値になる。
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
			// SHA-256 は必ず存在するが、万一の場合は空でない適当な値で代替する
			return Integer.toHexString(path.hashCode());
		}
	}

	private synchronized void load() {
		if (loadAttempted) {
			return;
		}
		loadAttempted = true;

		if (musicDataPath == null || !Files.isRegularFile(musicDataPath)) {
			logger.info("IIDX music_data.json was not found: {}", musicDataPath);
			return;
		}

		List<IIDXChartMeta> parsed = new ArrayList<IIDXChartMeta>();
		Map<String, IIDXChartMeta> byId = new HashMap<String, IIDXChartMeta>();
		try {
			String json = new String(Files.readAllBytes(musicDataPath), StandardCharsets.UTF_8);
			JsonValue root = new JsonReader().parse(json);
			JsonValue data = root.get("data");
			if (data == null) {
				logger.error("{} has no 'data' array", musicDataPath);
				return;
			}
			for (JsonValue song = data.child; song != null; song = song.next) {
				IIDXChartMeta meta = parseSong(song);
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
		logger.info("Loaded {} IIDX songs from {}", charts.size(), musicDataPath);
	}

	private static IIDXChartMeta parseSong(JsonValue song) {
		String title = song.getString("title", "").trim();
		String songId = Integer.toString(intValue(song, "song_id"));
		if (title.isEmpty() || "0".equals(songId)) {
			return null;
		}
		return new IIDXChartMeta(songId, title, song.getString("artist", ""), song.getString("genre", ""),
				intValue(song, "game_version"), intValue(song, "volume"),
				level(song, Difficulty.SPB), level(song, Difficulty.SPN), level(song, Difficulty.SPH),
				level(song, Difficulty.SPA), level(song, Difficulty.SPL), level(song, Difficulty.DPB),
				level(song, Difficulty.DPN), level(song, Difficulty.DPH), level(song, Difficulty.DPA),
				level(song, Difficulty.DPL));
	}

	/** レベルは "SPA_level" のように大文字の難易度トークン+接尾辞で格納されている */
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
