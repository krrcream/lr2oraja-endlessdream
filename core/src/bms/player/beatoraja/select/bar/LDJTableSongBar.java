package bms.player.beatoraja.select.bar;

import bms.player.beatoraja.song.SongData;

/**
 * LDJ 难度表内的谱面条，在 {@link SongBar} 的基础上多保存表 json 中的 difficultyValue。
 *
 * 表的分组栏启用排序后，LEVEL 排序按 difficultyValue 进行，
 * 而皮肤显示的 LEVEL 仍旧取 {@link SongData#getLevel()}，显示与排序的来源互不相同，
 * 因此需要把该值单独保存在这里。本类只在 LDJ 难度表内使用，表以外的 {@link SongBar} 完全不受影响。
 *
 * @author endlessdream
 */
public class LDJTableSongBar extends SongBar {

	/** 表 json 中的 difficultyValue，未指定时为 0。只用于排序，不用于显示 */
	private final float difficultyValue;

	public LDJTableSongBar(SongData song, float difficultyValue) {
		super(song);
		this.difficultyValue = difficultyValue;
	}

	/** 决定表内排列顺序的值，也可能为负值 */
	public float getDifficultyValue() {
		return difficultyValue;
	}
}
