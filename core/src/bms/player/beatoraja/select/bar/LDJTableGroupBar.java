package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import bms.player.beatoraja.ldj.LDJTableData;
import bms.player.beatoraja.ldj.LDJTableProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * 对应 LDJ 难度表 1 个分组的虚拟文件夹.子项是实际的 {@link SongBar}.
 *
 * 表的分组名直接成为该栏的标题，其下排列表中指定的谱面.
 * 谱面由 {@link LDJTableProvider#createSongBar(LDJTableData.Group, LDJTableData.Chart)} 创建，因此
 * music_data.json 中没有的曲目，以及没有该难度谱面的曲目会在这里被剔除.
 * 生成时会把分组名一并传下去，让分组名也显示在该栏曲目的曲名之后.
 * 对于 1 首曲子都不剩的分组，{@link #createIfAvailable} 会返回 null，
 * 以避免变成选中后什么也不会发生的死栏.
 *
 * 该栏启用排序，因此实际显示顺序由玩家当前的排序模式决定.
 * 下方创建时的 difficultyValue 升序只是基准顺序，玩家切换排序模式后就会被覆盖.
 * LEVEL 排序在表内改按 difficultyValue 比较(见 {@link bms.player.beatoraja.select.BarSorter})，
 * 皮肤显示的 LEVEL 仍取曲目本身的 LEVEL.
 *
 * @author endlessdream
 */
public class LDJTableGroupBar extends FolderBar {

	/** difficultyValue 升序.取值相同时通过稳定排序保持 json 的排列顺序 */
	private static final Comparator<LDJTableData.Chart> DIFFICULTY_VALUE_ORDER = new Comparator<LDJTableData.Chart>() {
		@Override
		public int compare(LDJTableData.Chart a, LDJTableData.Chart b) {
			return Float.compare(a.getDifficultyValue(), b.getDifficultyValue());
		}
	};

	private final SongBar[] songs;

	private LDJTableGroupBar(MusicSelector selector, String title, SongBar[] songs) {
		super(selector, createFolderData(title), "");
		setSortable(true);
		this.songs = songs;
	}

	/**
	 * @return 若表内谱面 1 首都没剩下则返回 null
	 */
	public static LDJTableGroupBar createIfAvailable(MusicSelector selector, LDJTableProvider provider,
			LDJTableData.Group group) {
		if (selector == null || provider == null || group == null) {
			return null;
		}

		final List<LDJTableData.Chart> charts = new ArrayList<LDJTableData.Chart>(group.getCharts());
		Collections.sort(charts, DIFFICULTY_VALUE_ORDER);
		final List<SongBar> songs = new ArrayList<SongBar>();
		for (LDJTableData.Chart chart : charts) {
			final SongBar bar = provider.createSongBar(group, chart);
			if (bar != null) {
				songs.add(bar);
			}
		}
		if (songs.isEmpty()) {
			return null;
		}

		return new LDJTableGroupBar(selector, group.getName(), songs.toArray(new SongBar[0]));
	}

	private static FolderData createFolderData(String title) {
		final FolderData folder = new FolderData();
		folder.setTitle(title);
		folder.setPath("");
		return folder;
	}

	@Override
	public Bar[] getChildren() {
		return songs;
	}

	@Override
	public void updateFolderStatus() {
	}
}
