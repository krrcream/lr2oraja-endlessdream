package bms.player.beatoraja.iidx;

import bms.player.beatoraja.iidx.IIDXChartRef.Difficulty;

/**
 * music_data.json に含まれる1曲分のメタデータ。
 * レベルは譜面ごと、それ以外は曲単位の情報。
 *
 * @author endlessdream
 */
public class IIDXChartMeta {

	private final String songId;
	private final String songIdDisplay;
	private final String title;
	private final String artist;
	private final String genre;
	private final int gameVersion;
	private final int volume;

	private final int spbLevel;
	private final int spnLevel;
	private final int sphLevel;
	private final int spaLevel;
	private final int splLevel;
	private final int dpbLevel;
	private final int dpnLevel;
	private final int dphLevel;
	private final int dpaLevel;
	private final int dplLevel;

	public IIDXChartMeta(String songId, String title, String artist, String genre, int gameVersion, int volume,
			int spbLevel, int spnLevel, int sphLevel, int spaLevel, int splLevel, int dpbLevel, int dpnLevel,
			int dphLevel, int dpaLevel, int dplLevel) {
		this.songId = songId;
		this.songIdDisplay = IIDXChartRef.toDisplayId(songId);
		this.title = title;
		this.artist = artist;
		this.genre = genre;
		this.gameVersion = gameVersion;
		this.volume = volume;
		this.spbLevel = spbLevel;
		this.spnLevel = spnLevel;
		this.sphLevel = sphLevel;
		this.spaLevel = spaLevel;
		this.splLevel = splLevel;
		this.dpbLevel = dpbLevel;
		this.dpnLevel = dpnLevel;
		this.dphLevel = dphLevel;
		this.dpaLevel = dpaLevel;
		this.dplLevel = dplLevel;
	}

	public String getSongId() {
		return songId;
	}

	public String getSongIdDisplay() {
		return songIdDisplay;
	}

	public String getTitle() {
		return title;
	}

	public String getArtist() {
		return artist;
	}

	public String getGenre() {
		return genre;
	}

	public int getGameVersion() {
		return gameVersion;
	}

	public int getVolume() {
		return volume;
	}

	/**
	 * 指定した難易度のレベル。未収録は0
	 */
	public int getLevel(Difficulty difficulty) {
		if (difficulty == null) {
			return 0;
		}
		switch (difficulty) {
		case SPB:
			return spbLevel;
		case SPN:
			return spnLevel;
		case SPH:
			return sphLevel;
		case SPA:
			return spaLevel;
		case SPL:
			return splLevel;
		case DPB:
			return dpbLevel;
		case DPN:
			return dpnLevel;
		case DPH:
			return dphLevel;
		case DPA:
			return dpaLevel;
		case DPL:
			return dplLevel;
		default:
			return 0;
		}
	}

	/**
	 * 指定した難易度の譜面が存在するか
	 */
	public boolean hasChart(Difficulty difficulty) {
		return getLevel(difficulty) > 0;
	}

	@Override
	public String toString() {
		return songIdDisplay + " " + title;
	}
}
