package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.ldj.LDJSongProvider;
import bms.player.beatoraja.ldj.LDJTableProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.song.SongData;

/**
 * LDJ(iidx2bms)的虚拟根文件夹(LDJ).
 *
 * 在开头放置难度表的入口({@link LDJTableFolderBar})，接着
 * 把由 iidx2bms 的 music_data.json 生成的 {@link SongBar} 按☆等级
 * 分别放入 {@link LDJStarFolderBar} 排列.把 SP☆1～☆12 与 DP☆1～☆12 放在同一画面中.
 *
 * 连 1 个难度表 json 都读不到时不插入 Table 栏.☆栏的
 * 构成不会因有无表而改变.
 *
 * 合成 {@link FolderData} 并继承 {@link FolderBar} 的理由，以及合成文件夹的
 * path 设为空字符串的理由，与 {@link LDJStarFolderBar} 相同.
 *
 * 该文件夹自身禁用排序.☆栏需要按标题顺序排列，但
 * {@link BarSorter} 会对非 SongBar 之间按标题进行比较，因此若保持启用
 * 就会被打乱成 ☆1, ☆10, ☆11, ☆12, ☆2... 这种顺序.各☆栏中的乐曲仍照常排序.
 *
 * @author endlessdream
 */
public class LDJFolderBar extends FolderBar {

	/** 作为☆等级处理的最大值.超过该等级的会被归为 ☆12 */
	private static final int MAX_STAR = 12;

	private final Bar[] children;

	/**
	 * @param selector 为创建子☆栏而传递
	 * @param provider 为查询难度表 json 的谱面而传递
	 */
	public LDJFolderBar(MusicSelector selector, LDJSongProvider provider, SongBar[] spBars, SongBar[] dpBars) {
		super(selector, createFolderData("LDJ"), "");
		setSortable(false);
		final List<Bar> stars = new ArrayList<Bar>();
		final Bar table = LDJTableFolderBar.createIfAvailable(selector, new LDJTableProvider(provider));
		if (table != null) {
			stars.add(table);
		}
		addStarBars(stars, selector, spBars, "SP☆");
		addStarBars(stars, selector, dpBars, "DP☆");
		this.children = stars.toArray(new Bar[0]);
	}

	private static FolderData createFolderData(String title) {
		final FolderData folder = new FolderData();
		folder.setTitle(title);
		folder.setPath("");
		return folder;
	}

	/**
	 * 只把 ☆1～☆12 中有 1 首以上乐曲的等级栏按升序追加.
	 *
	 * 空的☆栏不会被 BarManager 的删除过滤器(仅对 SongBar/GradeBar 生效)去除，
	 * 会变成选中后什么也不会发生的死栏，因此不创建.
	 */
	private static void addStarBars(List<Bar> out, MusicSelector selector, SongBar[] bars, String prefix) {
		if (bars == null) {
			return;
		}
		for (int level = 1; level <= MAX_STAR; level++) {
			final List<SongBar> bucket = new ArrayList<SongBar>();
			for (SongBar bar : bars) {
				if (starLevel(bar) == level) {
					bucket.add(bar);
				}
			}
			if (!bucket.isEmpty()) {
				out.add(new LDJStarFolderBar(selector, prefix + level, bucket.toArray(new SongBar[0])));
			}
		}
	}

	/** level 未设置(0 以下)时为 ☆1，超过 ☆12 时归为 ☆12 */
	private static int starLevel(SongBar bar) {
		final SongData song = bar.getSongData();
		final int level = song != null ? song.getLevel() : 0;
		if (level < 1) {
			return 1;
		}
		return Math.min(level, MAX_STAR);
	}

	@Override
	public Bar[] getChildren() {
		return children;
	}

	@Override
	public void updateFolderStatus() {
	}
}
