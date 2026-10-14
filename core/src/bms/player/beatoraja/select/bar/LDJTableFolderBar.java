package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bms.player.beatoraja.ldj.LDJTableData;
import bms.player.beatoraja.ldj.LDJTableProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * 作为 LDJ 难度表入口的虚拟文件夹(出现在 LDJ 开头的 "Table").
 *
 * 当前目录下 {@code LDJ_table} 中的 json1 个文件与子项的
 * {@link LDJTableFileBar} 1 个对应.若目录不存在，连 1 个 json 都读不到，
 * 等情况时，{@link #createIfAvailable} 会返回 null，因此 LDJ 的根下
 * 不会插入任何内容.该栏也禁用排序.
 *
 * @author endlessdream
 */
public class LDJTableFolderBar extends FolderBar {

	/** 在 LDJ 的根中最上方显示的栏名 */
	private static final String TITLE = "Table";

	private static final Logger logger = LoggerFactory.getLogger(LDJTableFolderBar.class);

	private final Bar[] children;

	private LDJTableFolderBar(MusicSelector selector, Bar[] children) {
		super(selector, createFolderData(TITLE), "");
		setSortable(false);
		this.children = children;
	}

	/**
	 * @return 若没有 {@code LDJ_table}，或没有 1 个可读取的表则返回 null
	 */
	public static LDJTableFolderBar createIfAvailable(MusicSelector selector, LDJTableProvider provider) {
		if (selector == null || provider == null || !provider.isAvailable()) {
			return null;
		}

		try {
			final List<Bar> tables = new ArrayList<Bar>();
			for (LDJTableData data : provider.getTables()) {
				final LDJTableFileBar bar = LDJTableFileBar.createIfAvailable(selector, provider, data);
				if (bar != null) {
					tables.add(bar);
				}
			}
			if (tables.isEmpty()) {
				return null;
			}

			return new LDJTableFolderBar(selector, tables.toArray(new Bar[0]));
		} catch (RuntimeException e) {
			logger.error("Failed to build the LDJ difficulty table bar: {}", e.getMessage());
			return null;
		}
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
