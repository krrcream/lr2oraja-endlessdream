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
 * LDJ難易度表の入口となる仮想フォルダ(LDJ の先頭に出る "Table")。
 *
 * カレントディレクトリの {@code LDJ_table} 配下のjson1ファイルが子の
 * {@link LDJTableFileBar} 1つに対応する。ディレクトリが無い、jsonが1つも読めない、
 * といった場合は {@link #createIfAvailable} が null を返すので、LDJのルートには
 * 何も挿さらない。この欄もソートを無効化する。
 *
 * @author endlessdream
 */
public class LDJTableFolderBar extends FolderBar {

	/** LDJ のルートで一番上に出す欄の名前 */
	private static final String TITLE = "Table";

	private static final Logger logger = LoggerFactory.getLogger(LDJTableFolderBar.class);

	private final Bar[] children;

	private LDJTableFolderBar(MusicSelector selector, Bar[] children) {
		super(selector, createFolderData(TITLE), "");
		setSortable(false);
		this.children = children;
	}

	/**
	 * @return {@code LDJ_table} が無い、または読める表が1つも無かった場合はnull
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
