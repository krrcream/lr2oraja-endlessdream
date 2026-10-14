package bms.player.beatoraja.ldj;

import bms.model.Mode;

/**
 * 虚拟LDJ文件夹所处理的单个谱面引用，不存在实体文件，
 * 通过 {@code iidx://<songId>/<difficulty>} 形式的路径进行识别.
 *
 * @author endlessdream
 */
public class LDJChartRef {

	public static final String SCHEME = "iidx://";

	/**
	 * LDJ 的难度，token 与 cli_convert.py 返回的清单共通
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
		 * BMS 的 #DIFFICULTY 值(1=BEGINNER ... 5=LEGGENDARIA)
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

	public LDJChartRef(String songId, Difficulty difficulty) {
		this.songId = songId;
		this.songIdDisplay = toDisplayId(songId);
		this.difficulty = difficulty;
	}

	public String getSongId() {
		return songId;
	}

	/**
	 * 4 位补零后的显示用 ID(5 位以上保持原样)
	 */
	public String getSongIdDisplay() {
		return songIdDisplay;
	}

	public Difficulty getDifficulty() {
		return difficulty;
	}

	/**
	 * 虚拟谱面的路径表示
	 */
	public String getPath() {
		return SCHEME + songId + "/" + difficulty.getToken();
	}

	@Override
	public String toString() {
		return getPath();
	}

	/**
	 * 判断是否为虚拟路径
	 */
	public static boolean isLDJPath(String path) {
		return path != null && path.startsWith(SCHEME);
	}

	/**
	 * 解析虚拟路径，格式非法时返回 null
	 */
	public static LDJChartRef parse(String path) {
		if (!isLDJPath(path)) {
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
		return new LDJChartRef(songId, difficulty);
	}

	/**
	 * 转换为显示用 ID.
	 *
	 * 与 iidx2bms 的 search_engine._display_song_id 采用相同规则
	 * (4 位的 ID 在开头补 '0' 变为 5 位，其余保持原样).
	 * 转换侧会根据该显示 ID 拼装素材文件名，因此规则一旦出现偏差，
	 * 就会变成 "Chart source files not found".
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
