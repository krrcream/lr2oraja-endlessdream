package bms.player.beatoraja.iidx;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import bms.player.beatoraja.iidx.IIDXChartRef.Difficulty;

/**
 * iidx-pro-difficulty-table 形式(json)の難易度表1ファイル分の内容。
 *
 * <p>beatoraja ネイティブの難易度表({@code TableData})とは別形式・別実装。ハッシュによる
 * 譜面の同一性判定は行わず、musicId と chartType の組だけで譜面を指す。</p>
 *
 * <p>値はすべて読み込み時に確定する不変オブジェクト。json の並び順をそのまま保持する。</p>
 *
 * @author endlessdream
 */
public final class IIDXTableData {

	private final Path source;

	private final String title;

	private final String description;

	private final String playStyle;

	private final List<Group> groups;

	public IIDXTableData(Path source, String title, String description, String playStyle, List<Group> groups) {
		this.source = source;
		this.title = title == null ? "" : title;
		this.description = description;
		this.playStyle = playStyle == null ? "" : playStyle;
		this.groups = groups == null ? Collections.<Group>emptyList() : Collections.unmodifiableList(groups);
	}

	/** 読み込み元のjsonファイル。ログと表示用 */
	public Path getSource() {
		return source;
	}

	public String getTitle() {
		return title;
	}

	/** 表の説明。未設定ならnull */
	public String getDescription() {
		return description;
	}

	/** "SP" か "DP"。絞り込みには使わず、記録用 */
	public String getPlayStyle() {
		return playStyle;
	}

	public List<Group> getGroups() {
		return groups;
	}

	/**
	 * 一覧に出す名前。json の title が無い場合はファイル名(拡張子なし)で代用する
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
	 * 表の中のグループ1つ。グループ名がそのまま選曲画面のフォルダ名になる
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

		/** 元ゲーム名(例 "ereter")。表示には使わない */
		public String getGameName() {
			return gameName;
		}

		public List<Chart> getCharts() {
			return charts;
		}
	}

	/**
	 * 表が指す譜面1つ。musicId と chartType が譜面の同一性を決める
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

		/** musicId を文字列にしたもの。music_data.json 側の song_id と同じ表記 */
		public String getSongId() {
			return songId;
		}

		/** chartType を対応付けた難易度。対応が取れない場合はnull */
		public Difficulty getDifficulty() {
			return difficulty;
		}

		/** 表の中での並び順を決める値。負の値もありうる */
		public float getDifficultyValue() {
			return difficultyValue;
		}

		/** 難易度値から派生したサブタグ。無指定なら空文字 */
		public String getLabel() {
			return label;
		}

		/** 元の曲名。曲が見つからなかったときのログ用 */
		public String getTitle() {
			return title;
		}

		/** songId と chartType から譜面を特定できるか */
		public boolean isValid() {
			return songId != null && !songId.isEmpty() && difficulty != null;
		}
	}
}
