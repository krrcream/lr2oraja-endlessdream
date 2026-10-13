package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.ldj.LDJSongProvider;
import bms.player.beatoraja.ldj.LDJTableProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.song.SongData;

/**
 * LDJ(iidx2bms)の仮想ルートフォルダ(LDJ)。
 *
 * 先頭に難易度表の入口({@link LDJTableFolderBar})を置き、続けて
 * iidx2bms の music_data.json から生成した {@link SongBar} を ☆レベル別の
 * {@link LDJStarFolderBar} に分けて並べる。SP☆1〜☆12 と DP☆1〜☆12 を1画面に置く。
 *
 * 難易度表のjsonが1つも読めない場合は Table 欄を挿さらない。表の有無で☆欄の
 * 構成が変わることはない。
 *
 * {@link FolderData} を合成して {@link FolderBar} を継承する理由と、合成フォルダの
 * path を空文字にする理由は {@link LDJStarFolderBar} と同じ。
 *
 * このフォルダ自身はソートを無効化する。☆欄はタイトル順に並べる必要があるが、
 * {@link BarSorter} は非 SongBar 同士をタイトルで比較するため、有効なままだと
 * ☆1, ☆10, ☆11, ☆12, ☆2... と崩れる。各☆欄の中の楽曲は通常どおりソートされる。
 *
 * @author endlessdream
 */
public class LDJFolderBar extends FolderBar {

	/** ☆レベルとして扱う最大値。これを超えるレベルは☆12に丸める */
	private static final int MAX_STAR = 12;

	private final Bar[] children;

	/**
	 * @param selector 子の☆欄を作るために引き継ぐ
	 * @param provider 難易度表jsonの譜面を引くために引き継ぐ
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
	 * ☆1〜☆12 のうち1曲以上あるレベルの欄だけを昇順で追加する。
	 *
	 * 空の☆欄は BarManager の削除フィルタ(SongBar/GradeBar のみ対象)で除去されず、
	 * 選択しても何も起きないデッドバーになるため作らない。
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

	/** level が未設定(0以下)なら☆1、☆12を超えるなら☆12に丸める */
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
