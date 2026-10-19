package bms.player.beatoraja.pattern;

import bms.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * SP→DP 谱面转换（内存转换，不落盘）。
 *
 * <p>把一张 8-lane 的 SP 谱（BEAT_7K）转换成 16-lane 的 DP 谱（BEAT_14K）。
 * 每个 SP 键对应一个候选 DP lane 窗口，阶段1 在窗口内随机挑一个未被同 tick 占用的位置，把 7 键均匀摊到 14 轨；
 * 阶段2 再检测并就近修复死卡（同指上下排对）与合成纵连。</p>
 *
 * <p>转换结果按改谱处理：不计分、不提交 IR，但正常显示灯（由 BMSPlayer 设置 assist/score）。</p>
 */
public class SPDPConvertModifier extends PatternModifier {

    private static final Logger logger = LoggerFactory.getLogger(SPDPConvertModifier.class);

    /** 死卡判定窗口 = 1/3 拍（设计里的 16 tick）。 */
    private static final double WINDOW_BEAT = 1.0 / 3.0;

    /** 长键结束后锁键缓冲 = 1/4 拍（设计里的 12 tick）。 */
    private static final double LN_TAIL_BEAT = 1.0 / 4.0;

    /** 时间比较容差（μs）：避免取整误差让 16 分间隔的 note 落在窗口边缘而漏判。 */
    private static final long TIME_MARGIN_US = 20_000L;

    /** 每个 note 最多移动次数（防死循环）。 */
    private static final int MAX_MOVE = 2;

    /**
     * SP 键 lane(0..6) → 候选 DP lane 窗口（物理位置 §3 的 0..13 左→右换算成 BMS lane）。
     * 阶段1 在窗口内随机挑一个未被同 tick 占用的位置，把 7 键均匀摊到 14 轨。
     */
    private static final int[][] WINDOWS = {
            {6, 5, 4},          // SP1：物理 0,1,2 → 1P7,1P6,1P5
            {5, 4, 3, 2},       // SP2：物理 1,2,3,4 → 1P6,1P5,1P4,1P3
            {3, 2, 1, 0},       // SP3：物理 3,4,5,6 → 1P4,1P3,1P2,1P1
            {1, 0, 8, 9},       // SP4：物理 5,6,7,8 → 1P2,1P1,2P1,2P2
            {8, 9, 10, 11},     // SP5：物理 7,8,9,10 → 2P1,2P2,2P3,2P4
            {10, 11, 12, 13},   // SP6：物理 9,10,11,12 → 2P3,2P4,2P5,2P6
            {12, 13, 14},       // SP7：物理 11,12,13 → 2P5,2P6,2P7
    };

    /** 每侧死卡对，用 BMS lane 表示。1P {1,2}/{4,5}，2P {9,10}/{12,13}。 */
    private static final int[][][] DEAD_PAIRS = {
            {{1, 2}, {4, 5}},
            {{9, 10}, {12, 13}},
    };

    /** 每侧键 lane（不含皿）。1P 0..6，2P 8..14。 */
    private static final int[][] KEY_LANES = {
            {0, 1, 2, 3, 4, 5, 6},
            {8, 9, 10, 11, 12, 13, 14},
    };

    /** 无理皿的无理键 lane：皿在侧时这些键与皿冲突。左侧 1P4..1P7，右侧 2P1..2P4。 */
    private static final int[][] IRRATIONAL_KEYS = {
            {3, 4, 5, 6},       // 1P4,1P5,1P6,1P7
            {8, 9, 10, 11},     // 2P1,2P2,2P3,2P4
    };

    /** 下排键 lane（键号 1,3,5,7）：1P {0,2,4,6}，2P {8,10,12,14}。 */
    private static final boolean[] BOTTOM_ROW = new boolean[16];

    static {
        for (int lane : new int[]{0, 2, 4, 6, 8, 10, 12, 14}) {
            BOTTOM_ROW[lane] = true;
        }
    }

    private final Map<Note, Integer> noteSpKey = new HashMap<>();
    private final Map<Note, Integer> moveCount = new HashMap<>();

    private TimeLine[] tls;
    /** lane → 该 lane 上长键占用的时间区间（[startMicro, endMicro+tail]）。 */
    private final List<long[]>[] lnSpans = new List[16];

    /** 是否处理 jack（最终扫描，勾选 Process Jack 时）。 */
    private final boolean processJack;

    public SPDPConvertModifier(boolean processJack) {
        super();
        this.processJack = processJack;
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

        // 阶段 1：键 note 在候选窗口内随机分配（同 tick 撞位则排除重选）
        long[] lnLockUntil = new long[16];
        Map<LongNote, Integer> lnEndLane = new HashMap<>();
        java.util.Random rnd = new java.util.Random();
        for (SrcNote s : keys) {
            int dpLane = assignRandomLane(s, rnd, lnLockUntil, lnEndLane);
            s.tl.setNote(dpLane, s.note);
            noteSpKey.put(s.note, s.spLane);
        }

        // 阶段 2：死卡 + 合成纵连修复
        fixCollisions();

        // 阶段 3：皿放置（按窗口密度选边）
        placeScratches(scratches, model, rnd);

        // 阶段 4：无理皿修复
        fixIrrationalScratch(rnd);

        // 阶段 5：最终 jack 扫描（勾选 Process Jack 时）
        if (processJack) {
            fixRemainingJacks();
        }

        logger.info("SPtoDP converted: {} key notes, {} scratches", keys.size(), scratches.size());
    }

    /** 阶段 1 单 note 分配：在候选窗口内随机挑一个未被同 tick 占用、未被长音锁定的 lane。 */
    private int assignRandomLane(SrcNote s, java.util.Random rnd, long[] lnLockUntil, Map<LongNote, Integer> lnEndLane) {
        int[] window = WINDOWS[s.spLane];
        long t = s.tl.getMicroTime();

        if (s.note instanceof LongNote ln && ln.isEnd()) {
            Integer lane = lnEndLane.get(ln);
            return (lane != null) ? lane : window[0];
        }

        List<Integer> free = new ArrayList<>();
        for (int lane : window) {
            if (s.tl.getNote(lane) == null && lnLockUntil[lane] <= t) {
                free.add(lane);
            }
        }
        int lane = free.isEmpty() ? window[rnd.nextInt(window.length)] : free.get(rnd.nextInt(free.size()));

        if (s.note instanceof LongNote ln) {
            long lnEndMicro = ln.getPair() != null ? ln.getPair().getMicroTime() : t;
            long tailUs = beatToMicro(LN_TAIL_BEAT, s.tl.getBPM());
            lnLockUntil[lane] = Math.max(lnLockUntil[lane], lnEndMicro + tailUs);
            lnEndLane.put(ln.getPair(), lane);
        }
        return lane;
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
                moveCount.merge(n, 1, Integer::sum);
                return true;
            }
        }
        return false;
    }

    /** 分档搜索目标 lane：同侧同排 → 同侧跨排 → 同侧任意 → 跨手。每档内按 dpIndex 距离升序。 */
    private int findTarget(TimeLine tl, Note note, int from) {
        long t = tl.getMicroTime();
        double bpm = tl.getBPM();
        int side = sideOf(from);
        boolean bottom = BOTTOM_ROW[from];

        for (int lane : sameSideLanes(side, bottom, from)) {
            if (legal(tl, note, lane, t, bpm)) {
                return lane;
            }
        }
        for (int lane : sameSideLanes(side, !bottom, from)) {
            if (legal(tl, note, lane, t, bpm)) {
                return lane;
            }
        }
        for (int lane : sameSideLanes(side, null, from)) {
            if (legal(tl, note, lane, t, bpm)) {
                return lane;
            }
        }
        // 跨手
        for (int lane : keyLanesSorted(1 - side, from)) {
            if (legal(tl, note, lane, t, bpm)) {
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

    private boolean legal(TimeLine tl, Note note, int cand, long t, double bpm) {
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

    /** 阶段 3：皿按窗口密度选边（1P=lane7，2P=lane15），密度相等时随机。 */
    private void placeScratches(List<SrcNote> scratches, BMSModel model, java.util.Random rnd) {
        int prevSide = 0;
        Map<LongNote, Integer> lnSide = new HashMap<>();
        for (SrcNote s : scratches) {
            int side;
            if (s.note instanceof LongNote ln) {
                if (!ln.isEnd()) {
                    side = pickScratchSide(model, s.tl, rnd);
                    lnSide.put(ln.getPair(), side);
                } else {
                    Integer p = lnSide.get(ln);
                    side = (p != null) ? p : prevSide;
                }
            } else {
                side = pickScratchSide(model, s.tl, rnd);
            }
            int lane = (side == 0) ? 7 : 15;
            s.tl.setNote(lane, s.note);
            prevSide = side;
        }
    }

    private int pickScratchSide(BMSModel model, TimeLine tl, java.util.Random rnd) {
        long t = tl.getMicroTime();
        // 长音优先：有长音的手不能位移去搓皿，皿放对面
        boolean leftLn = hasLongNoteHeld(t, 0);
        boolean rightLn = hasLongNoteHeld(t, 1);
        if (leftLn && !rightLn) {
            return 1;
        }
        if (rightLn && !leftLn) {
            return 0;
        }
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
        if (density[0] < density[1]) {
            return 0;
        }
        if (density[1] < density[0]) {
            return 1;
        }
        return rnd.nextInt(2);
    }

    /** 阶段 4：无理皿修复。皿与同侧无理键的 note 冲突时，优先把 note 挪到对面手，挪不了就把皿挪到对面。 */
    private void fixIrrationalScratch(java.util.Random rnd) {
        for (TimeLine tl : tls) {
            long t = tl.getMicroTime();
            double bpm = tl.getBPM();
            resolveIrrationalSide(tl, t, bpm, 0, rnd);
            resolveIrrationalSide(tl, t, bpm, 1, rnd);
        }
    }

    /** 处理一侧的无理皿：规则1 挪 note，规则2 挪皿。 */
    private void resolveIrrationalSide(TimeLine tl, long t, double bpm, int side, java.util.Random rnd) {
        int scratchLane = (side == 0) ? 7 : 15;
        Note scratch = tl.getNote(scratchLane);
        if (scratch == null) {
            return;
        }
        List<Located> irrational = new ArrayList<>();
        for (int lane : IRRATIONAL_KEYS[side]) {
            Note n = tl.getNote(lane);
            if (n != null && !(n instanceof MineNote)) {
                irrational.add(new Located(tl, lane, n));
            }
        }
        if (irrational.isEmpty()) {
            return;
        }
        Set<Integer> used = new HashSet<>();
        for (Located loc : irrational) {
            int target = pickOppositeLane(tl, t, bpm, side, used, rnd);
            if (target >= 0) {
                // 规则1：note 挪到对面手（长音连 start/end 一起挪）
                moveNote(tl, loc.lane, loc.note, target);
                used.add(target);
            } else if (!(scratch instanceof LongNote)) {
                // 规则2：皿挪到对面（对面皿位空且对面无理键全空）
                int otherScratch = (side == 0) ? 15 : 7;
                if (tl.getNote(otherScratch) == null && oppositeIrrationalFree(tl, side)) {
                    tl.setNote(scratchLane, null);
                    tl.setNote(otherScratch, scratch);
                    break;
                }
            }
        }
    }

    /** 在对面手的键里随机挑一个「不占用、不生成卡死、没被选过」的 lane。 */
    private int pickOppositeLane(TimeLine tl, long t, double bpm, int side, Set<Integer> used, java.util.Random rnd) {
        List<Integer> candidates = new ArrayList<>();
        for (int lane : KEY_LANES[1 - side]) {
            if (tl.getNote(lane) != null) {
                continue;
            }
            if (used.contains(lane)) {
                continue;
            }
            if (createsDeadPairAt(lane, t, bpm)) {
                continue;
            }
            candidates.add(lane);
        }
        if (candidates.isEmpty()) {
            return -1;
        }
        return candidates.get(rnd.nextInt(candidates.size()));
    }

    /** 对面手的无理键是否全空（皿挪过去后不会生成新的无理皿）。 */
    private boolean oppositeIrrationalFree(TimeLine tl, int side) {
        for (int lane : IRRATIONAL_KEYS[1 - side]) {
            if (tl.getNote(lane) != null) {
                return false;
            }
        }
        return true;
    }

    /** 某侧是否有长音正被按住（含长键尾部缓冲）。 */
    private boolean hasLongNoteHeld(long t, int side) {
        for (int lane : KEY_LANES[side]) {
            if (inLnSpan(lane, t)) {
                return true;
            }
        }
        return false;
    }

    /** 挪动 note（长音连 start/end 一起挪到同一 lane）。 */
    private void moveNote(TimeLine tl, int from, Note note, int to) {
        tl.setNote(from, null);
        tl.setNote(to, note);
        if (note instanceof LongNote ln && !ln.isEnd()) {
            LongNote end = (LongNote) ln.getPair();
            if (end != null) {
                TimeLine endTl = findEndTimeline(end, from);
                if (endTl != null) {
                    endTl.setNote(from, null);
                    endTl.setNote(to, end);
                }
            }
        }
    }

    /** 找长音尾所在的时间线。 */
    private TimeLine findEndTimeline(LongNote end, int lane) {
        for (TimeLine tl : tls) {
            if (tl.getNote(lane) == end) {
                return tl;
            }
        }
        return null;
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

    /** 拍数 → 微秒（按给定 BPM，附带 20ms 容差）。 */
    private static long beatToMicro(double beat, double bpm) {
        return (long) (beat * 60_000_000.0 / bpm) + TIME_MARGIN_US;
    }

    /** 阶段 5：最终 jack 扫描。逐个把合成纵连挪到「不生成 jack、不卡死」的位置，挪不动就删进 BGM。 */
    private void fixRemainingJacks() {
        for (TimeLine tl : tls) {
            long t = tl.getMicroTime();
            double bpm = tl.getBPM();
            for (int[] lanes : KEY_LANES) {
                for (int lane : lanes) {
                    Note n = tl.getNote(lane);
                    if (n == null || n instanceof LongNote || n instanceof MineNote) {
                        continue;
                    }
                    if (!isJack(n, lane, t, bpm)) {
                        continue;
                    }
                    int target = findJackTarget(tl, n, lane, t, bpm);
                    if (target >= 0) {
                        tl.setNote(lane, null);
                        tl.setNote(target, n);
                    } else {
                        tl.setNote(lane, null);
                        dropToBgm(tl, n);
                    }
                }
            }
        }
    }

    /** 该 note 是否在窗口内与同 lane 的其它 note 构成合成纵连（不同 SP 列）。 */
    private boolean isJack(Note note, int lane, long t, double bpm) {
        int sp = noteSpKey.getOrDefault(note, -1);
        long w = beatToMicro(WINDOW_BEAT, bpm);
        for (TimeLine tl : tls) {
            Note n = tl.getNote(lane);
            if (n != null && n != note && Math.abs(tl.getMicroTime() - t) <= w
                    && !Objects.equals(noteSpKey.get(n), sp)) {
                return true;
            }
        }
        return false;
    }

    /** 找目标 lane：先同一手、再另一只手，要求不生成 jack、不卡死。 */
    private int findJackTarget(TimeLine tl, Note note, int from, long t, double bpm) {
        int side = sideOf(from);
        for (int lane : KEY_LANES[side]) {
            if (lane == from || tl.getNote(lane) != null || inLnSpan(lane, t)) {
                continue;
            }
            if (hasSyntheticJackAt(note, lane, t, bpm) || createsDeadPairAt(lane, t, bpm)) {
                continue;
            }
            return lane;
        }
        for (int lane : KEY_LANES[1 - side]) {
            if (tl.getNote(lane) != null || inLnSpan(lane, t)) {
                continue;
            }
            if (hasSyntheticJackAt(note, lane, t, bpm) || createsDeadPairAt(lane, t, bpm)) {
                continue;
            }
            return lane;
        }
        return -1;
    }

    /** 丢弃 note：keysound 写到背景音轨（BGM），只丢判定不丢声音。 */
    private void dropToBgm(TimeLine tl, Note note) {
        int wav = note.getWav();
        if (wav >= 0) {
            tl.addBackGroundNote(new NormalNote(wav));
        }
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
