package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.ldj.LDJChartMeta;
import bms.player.beatoraja.ldj.LDJChartRef.Difficulty;
import bms.player.beatoraja.ldj.LDJSongProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * LDJ 的 Versions 栏下单代的文件夹(或 Other)。
 *
 * 进入该代时才生成这一代所有 SP/DP 难度的 {@link SongBar}，避免 LDJ 根目录
 * 在启动时就额外创建上万个 bar。生成出的 bar 标记为模式过滤例外，
 * 因此同一栏中 SP 与 DP 会同时显示。
 *
 * 合成 {@link FolderData} 并把 path 设为空字符串的理由与 {@link LDJStarFolderBar} 相同。
 *
 * @author endlessdream
 */
public class LDJVersionFolderBar extends FolderBar {

    private final List<LDJChartMeta> charts;
    private SongBar[] songs;

    public LDJVersionFolderBar(MusicSelector selector, String title, List<LDJChartMeta> charts) {
        super(selector, createFolderData(title), "");
        this.charts = charts != null ? charts : List.<LDJChartMeta>of();
    }

    private static FolderData createFolderData(String title) {
        final FolderData folder = new FolderData();
        folder.setTitle(title);
        folder.setPath("");
        return folder;
    }

    @Override
    public Bar[] getChildren() {
        if (songs == null) {
            final List<SongBar> bars = new ArrayList<SongBar>();
            for (LDJChartMeta chart : charts) {
                for (Difficulty difficulty : Difficulty.values()) {
                    if (!chart.hasChart(difficulty)) {
                        continue;
                    }
                    final SongBar bar = new SongBar(LDJSongProvider.createSongData(chart, difficulty));
                    bar.setModeFilterExempt(true);
                    bars.add(bar);
                }
            }
            songs = bars.toArray(new SongBar[0]);
        }
        return songs;
    }

    @Override
    public void updateFolderStatus() {
    }
}
