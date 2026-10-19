package bms.player.beatoraja.pattern;

import bms.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * SP→DP 谱面转换（内存转换，不落盘）。
 *
 * <p>把一张 8-lane 的 SP 谱（BEAT_7K）转换成 16-lane 的 DP 谱（BEAT_14K）。
 * 每个 SP 键映射到一对相邻 DP 键（主位/副位），按"左是左、右是右"均匀展开；
 * 死卡（同指上下排对）与合成纵连在 1/3 拍窗口内被检测并就近修复。</p>
 *
 * <p>转换结果按改谱处理：不计分、不提交 IR，但正常显示灯（由 BMSPlayer 设置 assist/score）。</p>
 */
public class SPDPConvertModifier extends PatternModifier {

    private static final Logger logger = LoggerFactory.getLogger(SPDPConvertModifier.class);

    /** 死卡判定窗口 = 1/3 拍（设计里的 16 tick）。 */
    private static final double WINDOW_BEAT = 1.0 / 3.0;

    /** 长键结束后锁键缓冲 = 1/4 拍（设计里的 12 tick）。 */
    private static final double LN_TAIL_BEAT = 1.0 / 4.0;

    /** 每个 note 最多移动次数（防死循环）。 */
    private static final int MAX_MOVE = 2;

    /** SP 键 lane(0..6) → 主位 DP lane。SP1 最左 → 1P7(lane6)，SP7 最右 → 2P6(lane13)。 */
    private static final int[] PRIMARY = {6, 4, 2, 0, 9, 11, 13};

    /** SP 键 lane(0..6) → 副位 DP lane。 */
    private static final int[] SECONDARY = {5, 3, 1, 8, 10, 12, 14};

    /** 每侧死卡对（同指上下排：食指{2,3}、无名指{5,6}），用 lane 表示。1P {1,2}/{4,5}，2P {9,10}/{12,13}。 */
    private static final int[][][] DEAD_PAIRS = {
            {{1, 2}, {4, 5}},
            {{9, 10}, {12, 13}},
    };

    /** 每侧键 lane（不含皿）。1P 0..6，2P 8..14。 */
    private static final int[][] KEY_LANES = {
            {0, 1, 2, 3, 4, 5, 6},
            {8, 9, 10, 11, 12, 13, 14},
    };

    /** 下排键 lane（键号 1,3,5,7）：1P {0,2,4,6}，2P {8,10,12,14}。 */
    private static final boolean[] BOTTOM_ROW = new boolean[16];

    static {
        for (int lane : new int[]{0, 2, 4, 6, 8, 10, 12, 14}) {
            BOTTOM_ROW[lane] = true;
        }
    }

    private final Map<Note, Integer> noteSpKey = new HashMap<>();
    private final Map<Note, Integer> noteLane = new HashMap<>();
    private final Map<Note, Integer> moveCount = new HashMap<>();

    private TimeLine[] tls;
    /** lane → 该 lane 上长键占用的时间区间（[startMicro, endMicro+tail]）。 */
    private final List<long[]>[] lnSpans = new List[16];

    public SPDPConvertModifier() {
        super();
    }

    @Override
    public void modify(BMSModel model) {
        if (model.getMode() != Mode.BEAT_7K) {
            return;
        }

        if (!model.getTitle().startsWith("[SPtoDP]")) {
            model.setTitle("[SPtoDP]" + model.getTitle());
        }

        this.tls = model.getAllTimeLines();
        noteSpKey.clear();
        noteLane.clear();
        moveCount.clear();

        // 收集 SP note（lane 0..7：键 0..6，皿 7）
        List<SrcNote> keys = new ArrayList<>();
        List<SrcNote> scratches = new ArrayList<>();
        for (TimeLine tl : tls) {
            for (int lane = 0; lane < 8; lane++) {
                Note n = tl.getNote(lane);
                if (n != null) {
                    (lane == 7 ? scratches : keys).add(new SrcNote(tl, lane, n));
                }
            }
        }

        // 清空原 8 lane，再扩到 16 lane
        for (TimeLine tl : tls) {
            for (int lane = 0; lane < 8; lane++) {
                tl.setNote(lane, null);
            }
        }
        model.setMode(Mode.BEAT_14K);

        // 阶段 1：键 note 基准分配
        long[] lnLockUntil = new long[16];
        Map<LongNote, Integer> lnEndLane = new HashMap<>();
        for (SrcNote s : keys) {
            int dpLane = assignLane(s, lnLockUntil, lnEndLane);
            s.tl.setNote(dpLane, s.note);
            noteSpKey.put(s.note, s.spLane);
            noteLane.put(s.note, dpLane);
        }

        // 阶段 2：死卡 + 合成纵连修复
        fixCollisions();

        // 阶段 3：皿放置（按窗口密度选边）
        placeScratches(scratches, model);

        logger.info("SPtoDP converted: {} key notes, {} scratches", keys.size(), scratches.size());
    }

    /** 阶段 1 单 note 分配：主位优先，被长键锁定则副位。长键 start 选定后 end 跟随同 lane。 */
    private int assignLane(SrcNote s, long[] lnLockUntil, Map<LongNote, Integer> lnEndLane) {
        int primary = PRIMARY[s.spLane];
        int secondary = SECONDARY[s.spLane];
        long t = s.tl.getMicroTime();

        if (s.note instanceof LongNote ln) {
            if (!ln.isEnd()) {
                int lane = (lnLockUntil[primary] <= t) ? primary
                        : (lnLockUntil[secondary] <= t) ? secondary : primary;
                long lnEndMicro = ln.getPair() != null ? ln.getPair().getMicroTime() : t;
                long tailUs = beatToMicro(LN_TAIL_BEAT, s.tl.getBPM());
                lnLockUntil[lane] = Math.max(lnLockUntil[lane], lnEndMicro + tailUs);
                lnEndLane.put(ln.getPair(), lane);
                return lane;
            } else {
                Integer l = lnEndLane.get(ln);
                return (l != null) ? l : primary;
            }
        } else {
            return (lnLockUntil[primary] <= t) ? primary
                    : (lnLockUntil[secondary] <= t) ? secondary : primary;
        }
    }

    /** 阶段 2 主循环：反复扫描直到无修复。 */
    private void fixCollisions() {
        buildLnSpans();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int side = 0; side < 2; side++) {
                if (fixDeadPairs(side)) {
                    changed = true;
                    continue;
                }
                if (fixSyntheticJack(side)) {
                    changed = true;
                }
            }
        }
    }

    /** 构建每个 lane 上长键占用的时间区间。 */
    private void buildLnSpans() {
        for (int i = 0; i < 16; i++) {
            lnSpans[i] = new ArrayList<>();
        }
        for (TimeLine tl : tls) {
            for (int lane = 0; lane < 16; lane++) {
                Note n = tl.getNote(lane);
                if (n instanceof LongNote ln && !ln.isEnd()) {
                    long start = tl.getMicroTime();
                    long end = ln.getPair() != null ? ln.getPair().getMicroTime() : start;
                    long tail = beatToMicro(LN_TAIL_BEAT, tl.getBPM());
                    lnSpans[lane].add(new long[]{start, end + tail});
                }
            }
        }
    }

    /** 死卡对检测：同侧死卡对的两键在窗口内先后出现（含和弦，不要求相邻）即触发。 */
    private boolean fixDeadPairs(int side) {
        for (int[] pair : DEAD_PAIRS[side]) {
            long lastTime = Long.MIN_VALUE;
            Located last = null;
            for (TimeLine tl : tls) {
                for (int lane : pair) {
                    Note n = tl.getNote(lane);
                    if (n == null || n instanceof MineNote) {
                        continue;
                    }
                    long w = beatToMicro(WINDOW_BEAT, tl.getBPM());
                    if (last != null && last.lane != lane && tl.getMicroTime() - lastTime <= w) {
                        if (fix(last, new Located(tl, lane, n))) {
                            return true;
                        }
                    }
                    last = new Located(tl, lane, n);
                    lastTime = tl.getMicroTime();
                }
            }
        }
        return false;
    }

    /** 合成纵连检测：同 lane 窗口内出现 ≥2 个来自不同 SP 列的 note。 */
    private boolean fixSyntheticJack(int side) {
        for (int lane : KEY_LANES[side]) {
            long lastTime = Long.MIN_VALUE;
            Located last = null;
            for (TimeLine tl : tls) {
                Note n = tl.getNote(lane);
                if (n == null || n instanceof MineNote) {
                    continue;
                }
                long w = beatToMicro(WINDOW_BEAT, tl.getBPM());
                if (last != null && tl.getMicroTime() - lastTime <= w
                        && !Objects.equals(noteSpKey.get(last.note), noteSpKey.get(n))) {
                    if (fix(last, new Located(tl, lane, n))) {
                        return true;
                    }
                }
                last = new Located(tl, lane, n);
                lastTime = tl.getMicroTime();
            }
        }
        return false;
    }

    /** 尝试移动一对 note：先较晚的，再较早的。长键、地雷不移动。 */
    private boolean fix(Located first, Located second) {
        for (Located cur : new Located[]{second, first}) {
            Note n = cur.note;
            if (n instanceof LongNote || n instanceof MineNote) {
                continue;
            }
            if (moveCount.getOrDefault(n, 0) >= MAX_MOVE) {
                continue;
            }
            int target = findTarget(cur.tl, n, cur.lane);
            if (target >= 0) {
                cur.tl.setNote(cur.lane, null);
                cur.tl.setNote(target, n);
                noteLane.put(n, target);
                moveCount.merge(n, 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    /** 分档搜索目标 lane：同侧同排 → 同侧跨排 → 撤销④ → 跨手。每档内按 dpIndex 距离升序。 */
    private int findTarget(TimeLine tl, Note note, int from) {
        long t = tl.getMicroTime();
        double bpm = tl.getBPM();
        int side = sideOf(from);
        boolean bottom = BOTTOM_ROW[from];

        for (int lane : sameSideLanes(side, bottom, from)) {
            if (legal(tl, note, lane, t, bpm, true)) {
                return lane;
            }
        }
        for (int lane : sameSideLanes(side, !bottom, from)) {
            if (legal(tl, note, lane, t, bpm, true)) {
                return lane;
            }
        }
        // 撤销④：同侧任意
        for (int lane : sameSideLanes(side, null, from)) {
            if (legal(tl, note, lane, t, bpm, false)) {
                return lane;
            }
        }
        // 跨手
        for (int lane : keyLanesSorted(1 - side, from)) {
            if (legal(tl, note, lane, t, bpm, false)) {
                return lane;
            }
        }
        return -1;
    }

    private List<Integer> sameSideLanes(int side, Boolean bottom, int from) {
        List<Integer> list = new ArrayList<>();
        for (int lane : KEY_LANES[side]) {
            if (lane == from) {
                continue;
            }
            if (bottom != null && BOTTOM_ROW[lane] != bottom) {
                continue;
            }
            list.add(lane);
        }
        list.sort(Comparator.comparingInt(l -> Math.abs(dpIndex(l) - dpIndex(from))));
        return list;
    }

    private List<Integer> keyLanesSorted(int side, int from) {
        List<Integer> list = new ArrayList<>();
        for (int lane : KEY_LANES[side]) {
            list.add(lane);
        }
        list.sort(Comparator.comparingInt(l -> Math.abs(dpIndex(l) - dpIndex(from))));
        return list;
    }

    private boolean legal(TimeLine tl, Note note, int cand, long t, double bpm, boolean respectOrder) {
        if (tl.getNote(cand) != null) {
            return false; // 1. 目标位置该 tick 无 note
        }
        if (inLnSpan(cand, t)) {
            return false; // 2. 不在长键区间内
        }
        if (hasSyntheticJackAt(note, cand, t, bpm)) {
            return false; // 3. 不制造合成纵连
        }
        if (createsDeadPairAt(cand, t, bpm)) {
            return false; // 4. 不产生新死卡对
        }
        if (respectOrder && violatesOrder(note, cand)) {
            return false; // 5. ④ 左右顺序
        }
        return true;
    }

    private boolean inLnSpan(int lane, long t) {
        for (long[] span : lnSpans[lane]) {
            if (t >= span[0] && t <= span[1]) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSyntheticJackAt(Note note, int cand, long t, double bpm) {
        int sp = noteSpKey.getOrDefault(note, -1);
        long w = beatToMicro(WINDOW_BEAT, bpm);
        for (TimeLine tl : tls) {
            Note n = tl.getNote(cand);
            if (n != null && n != note && Math.abs(tl.getMicroTime() - t) <= w
                    && !Objects.equals(noteSpKey.get(n), sp)) {
                return true;
            }
        }
        return false;
    }

    private boolean createsDeadPairAt(int cand, long t, double bpm) {
        int partner = deadPairPartner(cand);
        if (partner == -1) {
            return false;
        }
        long w = beatToMicro(WINDOW_BEAT, bpm);
        for (TimeLine tl : tls) {
            if (tl.getNote(partner) != null && Math.abs(tl.getMicroTime() - t) <= w) {
                return true;
            }
        }
        return false;
    }

    /** ④ 左右顺序：spKey 更小的 note 其 dpIndex 必须更小。 */
    private boolean violatesOrder(Note note, int cand) {
        int sp = noteSpKey.getOrDefault(note, -1);
        int candDp = dpIndex(cand);
        for (Map.Entry<Note, Integer> e : noteSpKey.entrySet()) {
            Note other = e.getKey();
            if (other == note) {
                continue;
            }
            Integer otherLane = noteLane.get(other);
            if (otherLane == null) {
                continue;
            }
            int otherDp = dpIndex(otherLane);
            if (e.getValue() < sp && otherDp >= candDp) {
                return true;
            }
            if (e.getValue() > sp && otherDp <= candDp) {
                return true;
            }
        }
        return false;
    }

    /** 阶段 3：皿按窗口密度选边（1P=lane7，2P=lane15）。 */
    private void placeScratches(List<SrcNote> scratches, BMSModel model) {
        int prevSide = 0;
        Map<LongNote, Integer> lnSide = new HashMap<>();
        for (SrcNote s : scratches) {
            int side;
            if (s.note instanceof LongNote ln) {
                if (!ln.isEnd()) {
                    side = pickScratchSide(model, s.tl);
                    lnSide.put(ln.getPair(), side);
                } else {
                    Integer p = lnSide.get(ln);
                    side = (p != null) ? p : prevSide;
                }
            } else {
                side = pickScratchSide(model, s.tl);
            }
            int lane = (side == 0) ? 7 : 15;
            s.tl.setNote(lane, s.note);
            prevSide = side;
        }
    }

    private int pickScratchSide(BMSModel model, TimeLine tl) {
        long t = tl.getMicroTime();
        long w = beatToMicro(WINDOW_BEAT, tl.getBPM());
        int[] density = new int[2];
        for (TimeLine other : model.getAllTimeLines()) {
            if (Math.abs(other.getMicroTime() - t) > w) {
                continue;
            }
            for (int s = 0; s < 2; s++) {
                for (int lane : KEY_LANES[s]) {
                    if (other.getNote(lane) != null) {
                        density[s]++;
                    }
                }
                if (other.getNote(s == 0 ? 7 : 15) != null) {
                    density[s]++;
                }
            }
        }
        return (density[0] <= density[1]) ? 0 : 1;
    }

    private int sideOf(int lane) {
        return (lane <= 7) ? 0 : 1;
    }

    private int deadPairPartner(int lane) {
        for (int[][] pairs : DEAD_PAIRS) {
            for (int[] p : pairs) {
                if (p[0] == lane) {
                    return p[1];
                }
                if (p[1] == lane) {
                    return p[0];
                }
            }
        }
        return -1;
    }

    /** lane → 物理 dpIndex（左→右 0..13），皿为 -1。 */
    private static int dpIndex(int lane) {
        if (lane <= 6) {
            return 6 - lane;
        }
        if (lane >= 8 && lane <= 14) {
            return lane - 1;
        }
        return -1;
    }

    /** 拍数 → 微秒（按给定 BPM）。 */
    private static long beatToMicro(double beat, double bpm) {
        return (long) (beat * 60_000_000.0 / bpm);
    }

    private static final class Located {
        final TimeLine tl;
        final int lane;
        final Note note;

        Located(TimeLine tl, int lane, Note note) {
            this.tl = tl;
            this.lane = lane;
            this.note = note;
        }
    }

    private static final class SrcNote {
        final TimeLine tl;
        final int spLane;
        final Note note;

        SrcNote(TimeLine tl, int spLane, Note note) {
            this.tl = tl;
            this.spLane = spLane;
            this.note = note;
        }
    }
}
