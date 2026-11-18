package bms.player.beatoraja.select.bar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import bms.player.beatoraja.ldj.LDJChartMeta;
import bms.player.beatoraja.ldj.LDJSongProvider;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.song.FolderData;

/**
 * LDJ 虚拟根下的 Versions 栏。
 *
 * 按 {@code music_data.json} 的 song_id 前段分组：1001/01001 属于第 1 代，
 * 31001 属于第 31 代。只建立 1..50 中实际有曲子的代数组，空的代数不会
 * 变成选中后什么也不发生的死栏；超过 50 代以及 80 开头的特殊 ID 全部
 * 归入最下方的 Other。
 *
 * 该栏自身禁用排序，保证 1,2,...,50,Other 的顺序；各代内部的乐曲仍照常排序。
 *
 * @author endlessdream
 */
public class LDJVersionGroupBar extends FolderBar {

    /** 未来新增代数时无需改代码的上限 */
    private static final int MAX_VERSION = 50;

    /** 8000..8999 是 IIDX 的特殊 ID 段，即使数值上属于第 8 代也放进 Other */
    private static final int SPECIAL_ID_MIN = 8000;
    private static final int SPECIAL_ID_MAX = 9000;

    private final Bar[] children;

    private LDJVersionGroupBar(MusicSelector selector, String title, Bar[] children) {
        super(selector, createFolderData(title), "");
        setSortable(false);
        this.children = children != null ? children : new Bar[0];
    }

    private static FolderData createFolderData(String title) {
        final FolderData folder = new FolderData();
        folder.setTitle(title);
        folder.setPath("");
        return folder;
    }

    /**
     * 创建 Versions 栏。没有可用曲目时返回 null。
     */
    public static Bar create(MusicSelector selector, LDJSongProvider provider) {
        final Map<Integer, List<LDJChartMeta>> byVersion = new TreeMap<Integer, List<LDJChartMeta>>();
        final List<LDJChartMeta> other = new ArrayList<LDJChartMeta>();

        for (LDJChartMeta chart : provider.getCharts()) {
            final int version = version(chart.getSongId());
            if (version < 1 || version > MAX_VERSION || isSpecial(chart.getSongId())) {
                other.add(chart);
            } else {
                byVersion.computeIfAbsent(version, key -> new ArrayList<LDJChartMeta>()).add(chart);
            }
        }

        final List<Bar> folders = new ArrayList<Bar>();
        for (Map.Entry<Integer, List<LDJChartMeta>> entry : byVersion.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                folders.add(new LDJVersionFolderBar(selector, Integer.toString(entry.getKey()), entry.getValue()));
            }
        }
        if (!other.isEmpty()) {
            folders.add(new LDJVersionFolderBar(selector, "Other", other));
        }
        return folders.isEmpty() ? null : new LDJVersionGroupBar(selector, "Versions",
                folders.toArray(new Bar[0]));
    }

    /** 1001/01001 -> 1，31001 -> 31；解析失败返回 -1 */
    private static int version(String songId) {
        final long id = id(songId);
        return id < 0 ? -1 : (int) (id / 1000);
    }

    private static boolean isSpecial(String songId) {
        final long id = id(songId);
        return id < 0 || (id >= SPECIAL_ID_MIN && id < SPECIAL_ID_MAX);
    }

    private static long id(String songId) {
        if (songId == null) {
            return -1;
        }
        try {
            return Long.parseLong(songId.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    @Override
    public Bar[] getChildren() {
        return children;
    }

    @Override
    public void updateFolderStatus() {
    }
}
