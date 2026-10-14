package bms.player.beatoraja.select.bar;

import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * LDJ(iidx2bms)按☆等级划分的虚拟文件夹.
 *
 * 由于没有实际文件，因此合成 {@link FolderData} 并继承 {@link FolderBar}
 * (BarRenderer 不绘制 FolderBar 以外的 DirectoryBar).
 * 合成文件夹的 path 设为空字符串.若设为 null，则 EventFactory 侧的
 * updateSong() / 打开资源管理器会抛异常.
 *
 * {@link FolderBar#updateFolderStatus()} 会进行以实际文件为前提的 CRC32 与 SQLite 查询，
 * 因此覆盖为空实现.
 *
 * @author endlessdream
 */
public class LDJStarFolderBar extends FolderBar {

	private final SongBar[] songs;

	public LDJStarFolderBar(MusicSelector selector, String title, SongBar[] songs) {
		super(selector, createFolderData(title), "");
		this.songs = songs != null ? songs : new SongBar[0];
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
