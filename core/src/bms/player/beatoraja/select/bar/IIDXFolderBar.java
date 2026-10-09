package bms.player.beatoraja.select.bar;

import bms.model.Mode;
import bms.player.beatoraja.select.MusicSelector;

/**
 * IIDX(iidx2bms)の仮想フォルダ。
 *
 * 実ファイルを持たず、iidx2bms の music_data.json から生成した {@link SongBar} を
 * 子に持つ。子は SP/DP で分けて保持し、{@link #getChildren()} が現在の選曲モードに
 * 応じてどちらかを返す。
 *
 * {@link DirectoryBar#getChildren(Mode, boolean)} はこのコードベースのどこからも
 * 呼ばれていないため、引数なしの {@link #getChildren()} 側でモードを解決する。
 *
 * @author endlessdream
 */
public class IIDXFolderBar extends DirectoryBar {

	private final String title;
	private final SongBar[] spBars;
	private final SongBar[] dpBars;

	/**
	 * @param selector 現在の選曲モードを取得するために使う。null の場合は SP 固定
	 */
	public IIDXFolderBar(MusicSelector selector, String title, SongBar[] spBars, SongBar[] dpBars) {
		super(selector);
		this.title = title;
		this.spBars = spBars != null ? spBars : new SongBar[0];
		this.dpBars = dpBars != null ? dpBars : new SongBar[0];
	}

	public IIDXFolderBar(String title, SongBar[] spBars, SongBar[] dpBars) {
		this(null, title, spBars, dpBars);
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Bar[] getChildren() {
		return isDoublePlay() ? dpBars : spBars;
	}

	/**
	 * 現在の選曲モードがDPかどうか。
	 *
	 * DirectoryBar と同じく selector.main.getPlayerConfig() 経由で参照する
	 * (PlayerConfig.getMode() はプレイヤーが選んでいるモードを返す)。
	 */
	private boolean isDoublePlay() {
		if (selector == null || selector.main == null) {
			return false;
		}
		Mode mode = selector.main.getPlayerConfig().getMode();
		return mode == Mode.BEAT_14K;
	}
}
