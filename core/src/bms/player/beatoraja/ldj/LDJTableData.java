package bms.player.beatoraja.ldj;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import bms.player.beatoraja.ldj.LDJChartRef.Difficulty;

/**
 * iidx-pro-difficulty-table 格式(json)的难度表单个文件的内容.
 *
 * <p>与 beatoraja 原生难度表({@code TableData})格式不同，实现也不同，不通过哈希
 * 判定谱面同一性，仅用 musicId 与 chartType 的组合来指向谱面.</p>
 *
 * <p>所有值都是读取时即确定的不可变对象，原样保持 json 的排列顺序.</p>
 *
 * @author endlessdream
 */
public final class LDJTableData {

	private final Path source;

	private final String title;

	private final String description;

	private final String playStyle;

	private final List<Group> groups;

	public LDJTableData(Path source, String title, String description, String playStyle, List<Group> groups) {
		this.source = source;
		this.title = title == null ? "" : title;
		this.description = description;
		this.playStyle = playStyle == null ? "" : playStyle;
		this.groups = groups == null ? Collections.<Group>emptyList() : Collections.unmodifiableList(groups);
	}

	/** 读取来源的 json 文件，用于日志与显示 */
	public Path getSource() {
		return source;
	}

	public String getTitle() {
		return title;
	}

	/** 表的说明，未设置时为 null */
	public String getDescription() {
		return description;
	}

	/** "SP" 或 "DP"，不用于筛选，仅作记录 */
	public String getPlayStyle() {
		return playStyle;
	}

	public List<Group> getGroups() {
		return groups;
	}

	/**
	 * 列表中显示的名称，json 中没有 title 时用文件名(不含扩展名)代替
	 */
	public String getDisplayTitle() {
		if (!title.isEmpty()) {
			return title;
		}
		if (source == null || source.getFileName() == null) {
			return "";
		}
		final String name = source.getFileName().toString();
		final int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/**
	 * 表中的一个分组，分组名直接成为选曲画面中的文件夹名
	 */
	public static final class Group {

		private final String name;

		private final String gameName;

		private final List<Chart> charts;

		public Group(String name, String gameName, List<Chart> charts) {
			this.name = name == null ? "" : name;
			this.gameName = gameName == null ? "" : gameName;
			this.charts = charts == null ? Collections.<Chart>emptyList() : Collections.unmodifiableList(charts);
		}

		public String getName() {
			return name;
		}

		/** 原始游戏名(例 "ereter")，不用于显示 */
		public String getGameName() {
			return gameName;
		}

		public List<Chart> getCharts() {
			return charts;
		}
	}

	/**
	 * 表所指向的单个谱面，musicId 与 chartType 决定谱面的同一性
	 */
	public static final class Chart {

		private final String songId;

		private final Difficulty difficulty;

		private final float difficultyValue;

		private final String label;

		private final String title;

		public Chart(String songId, Difficulty difficulty, float difficultyValue, String label, String title) {
			this.songId = songId;
			this.difficulty = difficulty;
			this.difficultyValue = difficultyValue;
			this.label = label == null ? "" : label;
			this.title = title == null ? "" : title;
		}

		/** musicId 的字符串形式，与 music_data.json 侧的 song_id 表记相同 */
		public String getSongId() {
			return songId;
		}

		/** 与 chartType 建立对应关系的难度，无法对应时为 null */
		public Difficulty getDifficulty() {
			return difficulty;
		}

		/** 决定表内排列顺序的值，也可能为负值 */
		public float getDifficultyValue() {
			return difficultyValue;
		}

		/** 由难度值派生的子标签，未指定时为空字符串 */
		public String getLabel() {
			return label;
		}

		/** 原始曲名，用于找不到曲目时的日志 */
		public String getTitle() {
			return title;
		}

		/** 能否根据 songId 与 chartType 确定谱面 */
		public boolean isValid() {
			return songId != null && !songId.isEmpty() && difficulty != null;
		}
	}
}
