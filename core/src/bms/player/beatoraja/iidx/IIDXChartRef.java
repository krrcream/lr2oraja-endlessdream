package bms.player.beatoraja.iidx;

import bms.model.Mode;

/**
 * 仮想IIDXフォルダが扱う1譜面の参照。実ファイルは存在せず、
 * {@code iidx://<songId>/<difficulty>} 形式のパスで識別する。
 *
 * @author endlessdream
 */
public class IIDXChartRef {

	public static final String SCHEME = "iidx://";

	/**
	 * IIDXの難易度。トークンはcli_convert.pyが返すマニフェストと共通
	 */
	public enum Difficulty {
		SPB("spb", 1, false),
		SPN("spn", 2, false),
		SPH("sph", 3, false),
		SPA("spa", 4, false),
		SPL("spl", 5, false),
		DPB("dpb", 1, true),
		DPN("dpn", 2, true),
		DPH("dph", 3, true),
		DPA("dpa", 4, true),
		DPL("dpl", 5, true);

		private final String token;
		private final int bmsDifficulty;
		private final boolean doublePlay;

		private Difficulty(String token, int bmsDifficulty, boolean doublePlay) {
			this.token = token;
			this.bmsDifficulty = bmsDifficulty;
			this.doublePlay = doublePlay;
		}

		public String getToken() {
			return token;
		}

		/**
		 * BMSの#DIFFICULTY値(1=BEGINNER ... 5=LEGGENDARIA)
		 */
		public int getBmsDifficulty() {
			return bmsDifficulty;
		}

		public boolean isDoublePlay() {
			return doublePlay;
		}

		public Mode getMode() {
			return doublePlay ? Mode.BEAT_14K : Mode.BEAT_7K;
		}

		public static Difficulty fromToken(String token) {
			if (token != null) {
				for (Difficulty difficulty : values()) {
					if (difficulty.token.equalsIgnoreCase(token)) {
						return difficulty;
					}
				}
			}
			return null;
		}
	}

	private final String songId;
	private final String songIdDisplay;
	private final Difficulty difficulty;

	public IIDXChartRef(String songId, Difficulty difficulty) {
		this.songId = songId;
		this.songIdDisplay = toDisplayId(songId);
		this.difficulty = difficulty;
	}

	public String getSongId() {
		return songId;
	}

	/**
	 * 4桁ゼロ埋めした表示用ID(5桁以上はそのまま)
	 */
	public String getSongIdDisplay() {
		return songIdDisplay;
	}

	public Difficulty getDifficulty() {
		return difficulty;
	}

	/**
	 * 仮想譜面のパス表現
	 */
	public String getPath() {
		return SCHEME + songId + "/" + difficulty.getToken();
	}

	@Override
	public String toString() {
		return getPath();
	}

	/**
	 * 仮想パスかどうかを判定する
	 */
	public static boolean isIIDXPath(String path) {
		return path != null && path.startsWith(SCHEME);
	}

	/**
	 * 仮想パスを解析する。形式が不正な場合はnull
	 */
	public static IIDXChartRef parse(String path) {
		if (!isIIDXPath(path)) {
			return null;
		}
		String body = path.substring(SCHEME.length());
		int separator = body.indexOf('/');
		if (separator <= 0 || separator == body.length() - 1) {
			return null;
		}
		String songId = body.substring(0, separator);
		Difficulty difficulty = Difficulty.fromToken(body.substring(separator + 1));
		if (difficulty == null) {
			return null;
		}
		return new IIDXChartRef(songId, difficulty);
	}

	/**
	 * 表示用IDへの変換。
	 *
	 * iidx2bms の search_engine._display_song_id と同じ規則に揃えてある
	 * (4桁のIDは先頭に'0'を足して5桁にする、それ以外はそのまま)。
	 * 変換側はこの表示IDから素材ファイル名を組み立てるため、規則がずれると
	 * "Chart source files not found" になる。
	 */
	public static String toDisplayId(String songId) {
		if (songId == null) {
			return "";
		}
		String trimmed = songId.trim();
		if (trimmed.length() == 4) {
			return "0" + trimmed;
		}
		return trimmed;
	}
}
