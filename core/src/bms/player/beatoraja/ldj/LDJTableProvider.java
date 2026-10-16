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
 * 读取当前目录下 {@code LDJ_table} 中的难度表 json.
 *
 * <p>读取的是 iidx-pro-difficulty-table 格式，与 beatoraja 原生的难度表不是一回事.
 * 不做哈希比对，而是把 musicId 与 chartType 的组合，与 {@link LDJSongProvider} 持有的
 * music_data.json 中的曲目元数据对照，生成 {@link SongBar}.</p>
 *
 * <p>在目录不存在，文件损坏，曲目找不到等情况下，
 * 都不抛出异常，只丢弃该表和该谱面.因为不能影响 LDJ 以外的行为.</p>
 *
 * @author endlessdream
 */
public final class LDJTableProvider {

	private static final Logger logger = LoggerFactory.getLogger(LDJTableProvider.class);

	/** 放置难度表 json 的目录名.相对于进程当前目录的路径 */
	public static final String TABLE_DIRECTORY = "LDJ_table";

	/** json 的扩展名 */
	private static final String JSON_SUFFIX = ".json";

	private final LDJSongProvider songs;

	private final Path tableDirectory;

	private boolean scanAttempted;

	private boolean available;

	/** 枚举出的 json 文件.按文件名升序 */
	private List<Path> files = Collections.emptyList();

	private boolean loadAttempted;

	private List<LDJTableData> tables = Collections.emptyList();

	public LDJTableProvider(LDJSongProvider songs) {
		this.songs = songs;
		this.tableDirectory = Paths.get(TABLE_DIRECTORY);
	}

	/** 当前引用的目录.用于日志 */
	public Path getTableDirectory() {
		return tableDirectory;
	}

	/**
	 * 是否处于可放置 1 个以上难度表的状态.只枚举目录，不解析内容
	 */
	public boolean isAvailable() {
		scan();
		return available;
	}

	/**
	 * 已读取的难度表(只读).不包含无法解析的文件
	 */
	public List<LDJTableData> getTables() {
		load();
		return tables;
	}

	/**
	 * 将 chartType 与难度对应起来.
	 *
	 * <p>0~4 为 SP 的 B/N/H/A/L，5~9 为 DP 的 B/N/H/A/L.超出范围则为 null</p>
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
	 * 把表中的 1 个谱面转成 {@link SongBar}.
	 *
	 * <p>找不到曲目元数据时，以及该难度的谱面不存在时，返回 null.
	 * 调用方应丢弃 null，不要生成一个曲目都不剩的分组.</p>
	 *
	 * @param group 该谱面所属的分组.分组名会显示在曲名之后
	 * @param chart 表所指向的谱面
	 * @return 对应虚拟路径的 SongBar.无法生成时为 null
	 */
	public SongBar createSongBar(LDJTableData.Group group, LDJTableData.Chart chart) {
		if (group == null || chart == null || !chart.isValid()) {
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
		// 曲名之后依次显示所属分组名与难度标签，中间用 1 个空格隔开
		final String groupName = group.getName();
		final String label = chart.getLabel();
		if (groupName.isEmpty()) {
			song.setSubtitle(label);
		} else if (label.isEmpty()) {
			song.setSubtitle(groupName);
		} else {
			song.setSubtitle(groupName + " " + label);
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
	 * 读取 1 个 json 文件.读不了时，以及没有任何可用分组时返回 null
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
	 * 读取 1 个分组.没有名称的，以及 1 个谱面都没有的，都不使用
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
	 * 读取 1 个谱面.musicId 与 chartType 两者都读不出来的，不使用
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
