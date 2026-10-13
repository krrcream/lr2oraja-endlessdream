package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.ldj.LDJTableData;
import bms.player.beatoraja.ldj.LDJTableProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * LDJ難易度表json1ファイル分の仮想フォルダ。子はグループの欄。
 *
 * タイトルは json の title。無ければファイル名(拡張子なし)で代用する。
 * 表を開くとグループ名が並び、グループを開くと譜面が並ぶ。
 *
 * どのグループにも譜面が1つも残らない表は {@link #createIfAvailable} が null を返し、
 * デッドバーにならないようにしている。この欄もソートを無効化する。
 *
 * @author endlessdream
 */
public class LDJTableFileBar extends FolderBar {

	private final Bar[] children;

	private LDJTableFileBar(MusicSelector selector, String title, Bar[] children) {
		super(selector, createFolderData(title), "");
		setSortable(false);
		this.children = children;
	}

	/**
	 * @return 使えるグループが1つも無かった場合はnull
	 */
	public static LDJTableFileBar createIfAvailable(MusicSelector selector, LDJTableProvider provider,
			LDJTableData data) {
		if (selector == null || provider == null || data == null) {
			return null;
		}

		final List<Bar> groups = new ArrayList<Bar>();
		for (LDJTableData.Group group : data.getGroups()) {
			final LDJTableGroupBar bar = LDJTableGroupBar.createIfAvailable(selector, provider, group);
			if (bar != null) {
				groups.add(bar);
			}
		}
		if (groups.isEmpty()) {
			return null;
		}

		return new LDJTableFileBar(selector, data.getDisplayTitle(), groups.toArray(new Bar[0]));
	}

	private static FolderData createFolderData(String title) {
		final FolderData folder = new FolderData();
		folder.setTitle(title);
		folder.setPath("");
		return folder;
	}

	@Override
	public Bar[] getChildren() {
		return children;
	}

	@Override
	public void updateFolderStatus() {
	}
}
