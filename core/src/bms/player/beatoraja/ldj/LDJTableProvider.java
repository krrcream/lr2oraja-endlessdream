package bms.player.beatoraja.ldj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import bms.player.beatoraja.ldj.LDJChartRef.Difficulty;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;

/**
 * カレントディレクトリの {@code LDJ_table} 配下にある難易度表jsonを読み込む。
 *
 * <p>読むのは iidx-pro-difficulty-table 形式で、beatoraja ネイティブの難易度表とは別物。
 * ハッシュ照合は行わず、musicId と chartType の組を {@link LDJSongProvider} が持つ
 * music_data.json の曲メタデータに突き合わせて {@link SongBar} を作る。</p>
 *
 * <p>ディレクトリが無い、ファイルが壊れている、曲が見つからない、といった場合は
 * 例外を投げずにその表・その譜面だけを落とす。LDJ 以外の挙動に影響を与えないため。</p>
 *
 * @author endlessdream
 */
public final class LDJTableProvider {

	private static final Logger logger = LoggerFactory.getLogger(LDJTableProvider.class);

	/** 難易度表jsonを置くディレクトリ名。プロセスのカレントディレクトリからの相対パス */
	public static final String TABLE_DIRECTORY = "LDJ_table";

	/** jsonの拡張子 */
	private static final String JSON_SUFFIX = ".json";

	private final LDJSongProvider songs;

	private final Path tableDirectory;

	private boolean scanAttempted;

	private boolean available;

	/** 列挙したjsonファイル。ファイル名の昇順 */
	private List<Path> files = Collections.emptyList();

	private boolean loadAttempted;

	private List<LDJTableData> tables = Collections.emptyList();

	public LDJTableProvider(LDJSongProvider songs) {
		this.songs = songs;
		this.tableDirectory = Paths.get(TABLE_DIRECTORY);
	}

	/** 参照しているディレクトリ。ログ用 */
	public Path getTableDirectory() {
		return tableDirectory;
	}

	/**
	 * 難易度表を1つ以上置ける状態か。ディレクトリの列挙だけを行い、中身は解析しない
	 */
	public boolean isAvailable() {
		scan();
		return available;
	}

	/**
	 * 読み込んだ難易度表(読み取り専用)。解析できないファイルは含まれない
	 */
	public List<LDJTableData> getTables() {
		load();
		return tables;
	}

	/**
	 * chartType を難易度に対応付ける。
	 *
	 * <p>0〜4がSPのB/N/H/A/L、5〜9がDPのB/N/H/A/L。範囲外はnull</p>
	 */
	public static Difficulty resolveDifficulty(int chartType) {
		switch (chartType) {
		case 0:
			return Difficulty.SPB;
		case 1:
			return Difficulty.SPN;
		case 2:
			return Difficulty.SPH;
		case 3:
			return Difficulty.SPA;
		case 4:
			return Difficulty.SPL;
		case 5:
			return Difficulty.DPB;
		case 6:
			return Difficulty.DPN;
		case 7:
			return Difficulty.DPH;
		case 8:
			return Difficulty.DPA;
		case 9:
			return Difficulty.DPL;
		default:
			return null;
		}
	}

	/**
	 * 表の譜面1つを {@link SongBar} にする。
	 *
	 * <p>曲メタデータが見つからない場合と、その難易度の譜面が存在しない場合はnullを返す。
	 * 呼び出し側はnullを落として、1曲も残らないグループを作らないこと。</p>
	 *
	 * @param chart 表が指す譜面
	 * @return 対応する仮想パスのSongBar。作れない場合はnull
	 */
	public SongBar createSongBar(LDJTableData.Chart chart) {
		if (chart == null || !chart.isValid()) {
			return null;
		}

		final String songId = chart.getSongId();
		final Difficulty difficulty = chart.getDifficulty();
		final LDJChartMeta meta = songs.getChart(songId);
		if (meta == null) {
			logger.info("LDJ chart {} ({}) was not found in music_data.json", songId, chart.getTitle());
			return null;
		}
		if (!meta.hasChart(difficulty)) {
			logger.info("LDJ chart {} has no {} chart", songId, difficulty.name());
			return null;
		}

		final SongData song = LDJSongProvider.createSongData(meta, difficulty);
		// 難易度値から派生したサブタグを、曲名の後ろにそのまま出す
		if (!chart.getLabel().isEmpty()) {
			song.setSubtitle(chart.getLabel());
		}
		return new SongBar(song);
	}

	private synchronized void scan() {
		if (scanAttempted) {
			return;
		}
		scanAttempted = true;

		if (!Files.isDirectory(tableDirectory)) {
			logger.info("LDJ difficulty table directory was not found: {}", tableDirectory.toAbsolutePath());
			return;
		}

		final List<Path> found = new ArrayList<Path>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(tableDirectory)) {
			for (Path path : stream) {
				if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(JSON_SUFFIX)) {
					found.add(path);
				}
			}
		} catch (IOException e) {
			logger.error("Failed to list {}: {}", tableDirectory, e.getMessage());
			return;
		}

		Collections.sort(found, new Comparator<Path>() {
			@Override
			public int compare(Path a, Path b) {
				return String.CASE_INSENSITIVE_ORDER.compare(a.getFileName().toString(), b.getFileName().toString());
			}
		});

		files = found;
		available = !found.isEmpty();
		if (available) {
			logger.info("Found {} LDJ difficulty tables in {}", found.size(), tableDirectory.toAbsolutePath());
		}
	}

	private synchronized void load() {
		if (loadAttempted) {
			return;
		}
		loadAttempted = true;
		scan();
		if (!available) {
			return;
		}

		final List<LDJTableData> parsed = new ArrayList<LDJTableData>();
		for (Path file : files) {
			final LDJTableData table = parse(file);
			if (table != null) {
				parsed.add(table);
			}
		}
		tables = Collections.unmodifiableList(parsed);
		logger.info("Loaded {} LDJ difficulty tables", tables.size());
	}

	/**
	 * jsonファイル1つを読む。読めない場合と、使えるグループが1つも無い場合はnull
	 */
	private static LDJTableData parse(Path file) {
		try {
			final String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			final JsonValue root = new JsonReader().parse(json);

			final List<LDJTableData.Group> groups = new ArrayList<LDJTableData.Group>();
			final JsonValue array = root.get("groups");
			if (array != null) {
				for (JsonValue group = array.child; group != null; group = group.next) {
					final LDJTableData.Group parsed = parseGroup(group);
					if (parsed != null) {
						groups.add(parsed);
					}
				}
			}
			if (groups.isEmpty()) {
				logger.warn("{} has no usable chart group", file);
				return null;
			}

			return new LDJTableData(file, stringValue(root, "title"), stringValue(root, "description"),
					stringValue(root, "playStyle"), groups);
		} catch (IOException e) {
			logger.error("Failed to read {}: {}", file, e.getMessage());
			return null;
		} catch (RuntimeException e) {
			logger.error("Failed to parse {}: {}", file, e.getMessage());
			return null;
		}
	}

	/**
	 * グループ1つを読む。名前が無いものと、譜面が1つも無いものは使わない
	 */
	private static LDJTableData.Group parseGroup(JsonValue group) {
		final String name = stringValue(group, "name").trim();
		if (name.isEmpty()) {
			logger.warn("LDJ difficulty table has a group without name");
			return null;
		}

		final List<LDJTableData.Chart> charts = new ArrayList<LDJTableData.Chart>();
		final JsonValue array = group.get("charts");
		if (array != null) {
			for (JsonValue chart = array.child; chart != null; chart = chart.next) {
				final LDJTableData.Chart parsed = parseChart(chart);
				if (parsed != null) {
					charts.add(parsed);
				}
			}
		}
		if (charts.isEmpty()) {
			return null;
		}

		return new LDJTableData.Group(name, stringValue(group, "gameName"), charts);
	}

	/**
	 * 譜面1つを読む。musicId と chartType の両方が読めないものは使わない
	 */
	private static LDJTableData.Chart parseChart(JsonValue chart) {
		final int musicId = intValue(chart, "musicId");
		if (musicId <= 0 || chart.get("chartType") == null) {
			return null;
		}
		final Difficulty difficulty = resolveDifficulty(intValue(chart, "chartType"));
		if (difficulty == null) {
			return null;
		}

		return new LDJTableData.Chart(Integer.toString(musicId), difficulty,
				(float) doubleValue(chart, "difficultyValue"), stringValue(chart, "label"),
				stringValue(chart, "title"));
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

	private static double doubleValue(JsonValue parent, String name) {
		JsonValue value = parent.get(name);
		if (value == null) {
			return 0;
		}
		try {
			return value.asDouble();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static String stringValue(JsonValue parent, String name) {
		JsonValue value = parent.get(name);
		if (value == null) {
			return "";
		}
		try {
			return value.asString();
		} catch (RuntimeException e) {
			return "";
		}
	}
}
