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
 * LDJ難易度表のグループ1つ分の仮想フォルダ。子は実際の {@link SongBar}。
 *
 * 表のグループ名がそのままこの欄のタイトルになり、配下に表が指定した譜面が並ぶ。
 * 譜面は {@link LDJTableProvider#createSongBar(LDJTableData.Chart)} で作るため、
 * music_data.json に無い曲・その難易度の譜面が無い曲はここで落ちる。
 * 1曲も残らないグループは {@link #createIfAvailable} が null を返し、
 * 選択しても何も起きないデッドバーにならないようにしている。
 *
 * 並び順は difficultyValue の昇順。表の中での並び順を決める値と定められており、
 * 同値の場合は json の並び順を保つ(Collections.sort は安定ソート)。
 * この欄はソートを無効化する。有効なままだとユーザーの BarSorter が表の並びを崩す。
 *
 * @author endlessdream
 */
public class LDJTableGroupBar extends FolderBar {

	/** difficultyValue 昇順。同値は安定ソートにより json の並び順のまま */
	private static final Comparator<LDJTableData.Chart> DIFFICULTY_VALUE_ORDER = new Comparator<LDJTableData.Chart>() {
		@Override
		public int compare(LDJTableData.Chart a, LDJTableData.Chart b) {
			return Float.compare(a.getDifficultyValue(), b.getDifficultyValue());
		}
	};

	private final SongBar[] songs;

	private LDJTableGroupBar(MusicSelector selector, String title, SongBar[] songs) {
		super(selector, createFolderData(title), "");
		setSortable(false);
		this.songs = songs;
	}

	/**
	 * @return 表の譜面が1つも残らなかった場合はnull
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
			final SongBar bar = provider.createSongBar(chart);
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
