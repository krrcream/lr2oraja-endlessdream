package bms.player.beatoraja.pattern;

import bms.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * DP→SP 谱面转换（内存转换，不落盘）。
 *
 * <p>第一版：逐 tick 单调挤压 + 第二次扫描清理。
 * 键：2Pk 折叠到 1Pk（键号对应），同 tick 撞键按物理左右序就近让位。
 * 皿：全部统一到 1P皿，同 tick 两个皿随机留一个。
 * 清理（第二次扫描）：把长键里的 note（含结尾 1/4 拍）挪出、把 jack 打散；
 * 挪不动时删上一行一个 note 再挪当前 note 过去（每行最多 3 次），仍无位置则删到背景音轨。</p>
 */
public class DPSPConvertModifier extends PatternModifier {

    private static final Logger logger = LoggerFactory.getLogger(DPSPConvertModifier.class);

    /** 是否处理 jack。 */
    private final boolean processJack;

    /** 时间比较容差（μs）：避免取整误差让 16 分间隔的 note 落在窗口边缘而漏判。 */
    private static final long TIME_MARGIN_US = 20_000L;

    private TimeLine[] tls;

    /** 每个 SP lane 的长音区间 [start, end + 1/4 拍]。 */
    private final List<long[]>[] lnSpans = new List[7];

    public DPSPConvertModifier(boolean processJack) {
        this.processJack = processJack;
    }

    @Override
    public void modify(BMSModel model) {
        if (model.getMode() != Mode.BEAT_14K) {
            return;
        }
        if (!model.getTitle().startsWith("[DPtoSP]")) {
            model.setTitle("[DPtoSP]" + model.getTitle());
        }

        this.tls = model.getAllTimeLines();
        java.util.Random rnd = new java.util.Random();
        Map<LongNote, Integer> lnLane = new HashMap<>();

        for (TimeLine tl : tls) {
            squeezeKeys(tl, lnLane);
            placeScratch(tl, rnd);
        }

        // 第二次扫描：长键冲突 + 重叠始终处理，jack 由 processJack 控制
        cleanup();

        model.setMode(Mode.BEAT_7K);
        logger.info("DPtoSP converted");
    }

    /** 逐 tick 单调挤压：14 键 → 7 键。 */
    private void squeezeKeys(TimeLine tl, Map<LongNote, Integer> lnLane) {
        List<SrcNote> keys = new ArrayList<>();
        for (int lane = 0; lane <= 6; lane++) {
            Note n = tl.getNote(lane);
            if (n != null) {
                keys.add(new SrcNote(lane, n));
            }
        }
        for (int lane = 8; lane <= 14; lane++) {
            Note n = tl.getNote(lane);
            if (n != null) {
                keys.add(new SrcNote(lane, n));
            }
        }
        if (keys.isEmpty()) {
            return;
        }

        // 长音尾排前面先占位（避免和普通 note 重叠），其余按物理左右序
        keys.sort((a, b) -> {
            boolean aEnd = a.note instanceof LongNote ln && ln.isEnd();
            boolean bEnd = b.note instanceof LongNote ln && ln.isEnd();
            if (aEnd != bEnd) {
                return aEnd ? -1 : 1;
            }
            return Integer.compare(dpIndex(a.dpLane), dpIndex(b.dpLane));
        });

        for (int lane = 0; lane <= 6; lane++) {
            tl.setNote(lane, null);
        }
        for (int lane = 8; lane <= 14; lane++) {
            tl.setNote(lane, null);
        }

        boolean[] occupied = new boolean[7];
        for (SrcNote s : keys) {
            if (s.note instanceof LongNote ln && ln.isEnd()) {
                Integer lane = lnLane.get(ln);
                if (lane != null && !occupied[lane]) {
                    occupied[lane] = true;
                    tl.setNote(lane, s.note);
                }
                continue;
            }
            int fold = foldLane(s.dpLane);
            int target = findFreeLane(occupied, fold);
            if (target < 0) {
                dropToBgm(tl, s.note);
                continue;
            }
            occupied[target] = true;
            tl.setNote(target, s.note);
            if (s.note instanceof LongNote ln) {
                lnLane.put(ln.getPair(), target);
            }
        }
    }

    /** 第二次扫描：把长键里的 note、jack 挪出；挪不动删上一行或删当前。 */
    private void cleanup() {
        buildLnSpans();
        for (int i = 0; i < tls.length; i++) {
            TimeLine tl = tls[i];
            long t = tl.getMicroTime();
            long w = beatToMicro(1.0 / 4.0, tl.getBPM()) + TIME_MARGIN_US;
            int deletedPrev = 0;
            for (int lane = 0; lane < 7; lane++) {
                Note n = tl.getNote(lane);
                if (n == null || n instanceof LongNote) {
                    continue;
                }
                boolean conflict = inLnSpan(lane, t) || (processJack && isJackAt(i, lane, w));
                if (!conflict) {
                    continue;
                }
                int target = findValidLane(tl, lane, t, w);
                if (target >= 0) {
                    tl.setNote(lane, null);
                    tl.setNote(target, n);
                    continue;
                }
                // 挪不动：删上一行一个 note 腾位置，把当前 note 挪过去（每行最多 3 次）
                if (deletedPrev < 3) {
                    int freed = findLaneToFree(tl, lane, t);
                    if (freed >= 0) {
                        TimeLine prevTl = findPrevNote(i, freed, w);
                        if (prevTl != null) {
                            Note prev = prevTl.getNote(freed);
                            prevTl.setNote(freed, null);
                            dropToBgm(prevTl, prev);
                            tl.setNote(lane, null);
                            tl.setNote(freed, n);
                            deletedPrev++;
                            continue;
                        }
                    }
                }
                // 三次都挪不动或长音冲突无解：删当前 note 到 BGM
                tl.setNote(lane, null);
                dropToBgm(tl, n);
            }
        }
    }

    /** 构建每个 SP lane 的长音区间 [start, end + 1/4 拍]。 */
    private void buildLnSpans() {
        for (int i = 0; i < 7; i++) {
            lnSpans[i] = new ArrayList<>();
        }
        for (TimeLine tl : tls) {
            for (int lane = 0; lane < 7; lane++) {
                Note n = tl.getNote(lane);
                if (n instanceof LongNote ln && !ln.isEnd()) {
                    long start = tl.getMicroTime();
                    long end = ln.getPair() != null ? ln.getPair().getMicroTime() : start;
                    long tail = beatToMicro(1.0 / 4.0, tl.getBPM()) + TIME_MARGIN_US;
                    lnSpans[lane].add(new long[]{start, end + tail});
                }
            }
        }
    }

    private boolean inLnSpan(int lane, long t) {
        for (long[] span : lnSpans[lane]) {
            if (t >= span[0] && t <= span[1]) {
                return true;
            }
        }
        return false;
    }

    /** 当前 note 是否与窗口内同 lane 的其它普通 note 构成 jack。 */
    private boolean isJackAt(int i, int lane, long w) {
        long t = tls[i].getMicroTime();
        for (int j = i - 1; j >= 0 && t - tls[j].getMicroTime() <= w; j--) {
            Note n = tls[j].getNote(lane);
            if (n != null && !(n instanceof LongNote)) {
                return true;
            }
        }
        return false;
    }

    /** 找窗口内前一个同 lane 的普通 note 所在时间线。 */
    private TimeLine findPrevNote(int i, int lane, long w) {
        long t = tls[i].getMicroTime();
        for (int j = i - 1; j >= 0 && t - tls[j].getMicroTime() <= w; j--) {
            Note n = tls[j].getNote(lane);
            if (n != null && !(n instanceof LongNote)) {
                return tls[j];
            }
        }
        return null;
    }

    /** 找相邻合法 lane：该 tick 空闲、不在长音内、不构成新 jack。 */
    private int findValidLane(TimeLine tl, int lane, long t, long w) {
        for (int d = 1; d < 7; d++) {
            int right = lane + d;
            if (right < 7 && valid(tl, right, t, w)) {
                return right;
            }
            int left = lane - d;
            if (left >= 0 && valid(tl, left, t, w)) {
                return left;
            }
        }
        return -1;
    }

    /** 找一个「当前 tick 空闲、不在长音内」的相邻 lane（删掉上一行的 note 后可挪过去）。 */
    private int findLaneToFree(TimeLine tl, int lane, long t) {
        for (int d = 1; d < 7; d++) {
            int right = lane + d;
            if (right < 7 && tl.getNote(right) == null && !inLnSpan(right, t)) {
                return right;
            }
            int left = lane - d;
            if (left >= 0 && tl.getNote(left) == null && !inLnSpan(left, t)) {
                return left;
            }
        }
        return -1;
    }

    private boolean valid(TimeLine tl, int lane, long t, long w) {
        if (tl.getNote(lane) != null) {
            return false;
        }
        if (inLnSpan(lane, t)) {
            return false;
        }
        return !hasNearbyNote(lane, t, w);
    }

    private boolean hasNearbyNote(int lane, long t, long w) {
        for (TimeLine tl : tls) {
            Note n = tl.getNote(lane);
            if (n != null && !(n instanceof LongNote) && tl.getMicroTime() != t
                    && Math.abs(tl.getMicroTime() - t) <= w) {
                return true;
            }
        }
        return false;
    }

    /** 从 fold 位置就近找空闲 lane（先右后左）。 */
    private static int findFreeLane(boolean[] occupied, int fold) {
        for (int d = 0; d < 7; d++) {
            int right = fold + d;
            if (right < 7 && !occupied[right]) {
                return right;
            }
            if (d > 0) {
                int left = fold - d;
                if (left >= 0 && !occupied[left]) {
                    return left;
                }
            }
        }
        return -1;
    }

    /** 丢弃 note：keysound 写到背景音轨（BGM）。 */
    private void dropToBgm(TimeLine tl, Note note) {
        int wav = note.getWav();
        if (wav >= 0) {
            tl.addBackGroundNote(new NormalNote(wav));
        }
    }

    /** 皿：统一到 1P皿（lane 7），同 tick 两个皿随机留一个。 */
    private void placeScratch(TimeLine tl, java.util.Random rnd) {
        Note s1 = tl.getNote(7);
        Note s2 = tl.getNote(15);
        tl.setNote(7, null);
        tl.setNote(15, null);
        Note scratch;
        if (s1 != null && s2 != null) {
            scratch = rnd.nextBoolean() ? s1 : s2;
        } else {
            scratch = (s1 != null) ? s1 : s2;
        }
        if (scratch != null) {
            tl.setNote(7, scratch);
        }
    }

    /** DP lane → SP lane（2P 折叠到 1P）。 */
    private static int foldLane(int dpLane) {
        return (dpLane <= 6) ? dpLane : dpLane - 8;
    }

    /** lane → 物理左右序（0 = 最左，13 = 最右）。 */
    private static int dpIndex(int lane) {
        if (lane <= 6) {
            return 6 - lane;
        }
        if (lane >= 8 && lane <= 14) {
            return lane - 1;
        }
        return -1;
    }

    /** 拍 → 微秒。 */
    private static long beatToMicro(double beat, double bpm) {
        return (long) (beat * 60_000_000.0 / bpm);
    }

    private static final class SrcNote {
        final int dpLane;
        final Note note;

        SrcNote(int dpLane, Note note) {
            this.dpLane = dpLane;
            this.note = note;
        }
    }
}
