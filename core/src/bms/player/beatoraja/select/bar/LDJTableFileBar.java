package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.ldj.LDJTableData;
import bms.player.beatoraja.ldj.LDJTableProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * 对应 LDJ 难度表 json1 个文件的虚拟文件夹.子项是分组的栏.
 *
 * 标题取 json 的 title.若没有，则用文件名(不含扩展名)代替.
 * 打开表后会列出分组名，打开分组后会列出谱面.
 *
 * 任何分组都没有剩下 1 首谱面的表，{@link #createIfAvailable} 会返回 null，
 * 以避免变成死栏.该栏也禁用排序.
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
	 * @return 若 1 个可用分组都没有则返回 null
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
