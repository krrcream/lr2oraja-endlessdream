package bms.player.beatoraja.select.bar;

import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * IIDX(iidx2bms)の☆レベル別の仮想フォルダ。
 *
 * 実ファイルを持たないため {@link FolderData} を合成して {@link FolderBar} を継承する
 * (BarRenderer は FolderBar 以外の DirectoryBar を描画しない)。
 * 合成フォルダの path は空文字にする。null にすると EventFactory 側の
 * updateSong() / エクスプローラオープンが例外になる。
 *
 * {@link FolderBar#updateFolderStatus()} は実ファイル前提の CRC32 と SQLite 問い合わせを
 * 行うため、何もしないよう上書きする。
 *
 * @author endlessdream
 */
public class IIDXStarFolderBar extends FolderBar {

	private final SongBar[] songs;

	public IIDXStarFolderBar(MusicSelector selector, String title, SongBar[] songs) {
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
