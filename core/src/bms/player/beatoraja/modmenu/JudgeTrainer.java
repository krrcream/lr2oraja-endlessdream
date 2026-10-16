package bms.player.beatoraja.modmenu;

import bms.model.Mode;
import bms.player.beatoraja.play.BMSPlayerRule;

public class JudgeTrainer {
    public static final String[] JUDGE_OPTIONS = new String[]{
            "EASY", "NORMAL", "HARD", "VERY_HARD", "IIDX-Like Type"
    };
    public static final int IIDX_LIKE_INDEX = JUDGE_OPTIONS.length - 1;

    // IIDX（大多数谱面）的判定窗口，单位微秒，顺序为 PGREAT、GREAT、GOOD、BAD。
    // 取值来自 iidx.org 的 1 / 2 / 7 / 15 帧 @60fps，即 16666.67 / 33333.33 / 116666.67 / 250000 微秒，
    // 四舍五入到最近的整数微秒。
    // 只保留 4 行：长条松手判定要求正好 4 行（LR2 也是这个行数）。
    public static final long[][] IIDX_WINDOW = new long[][]{
            {-16667, 16667}, {-33333, 33333}, {-116667, 116667}, {-250000, 250000}
    };
    // 5 rows: note / long note press judging, index 4 is the excessive POOR window
    // (kept as LR2's, since iidx.org does not state IIDX's exact value).
    public static final long[][] IIDX_NOTE = new long[][]{
            IIDX_WINDOW[0], IIDX_WINDOW[1], IIDX_WINDOW[2], IIDX_WINDOW[3], {0, 1000000}
    };

    private static boolean active;
    private static int judgeRank = 0;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean active) {
        JudgeTrainer.active = active;
    }

    public static int getJudgeRank() {
        return judgeRank;
    }

    public static void setJudgeRank(int judgeRank) {
        JudgeTrainer.judgeRank = judgeRank;
    }

    /**
     * IIDX-Like Type does not go through the judge rank percentage scaling at all; it replaces the
     * materialized judge windows directly in JudgeManager.
     */
    public static boolean isIidxLike() {
        return active && judgeRank == IIDX_LIKE_INDEX;
    }

    public static int getJudgeWindowRate(Mode mode) {
        if (judgeRank == IIDX_LIKE_INDEX) {
            // Not a valid judge window rate; caller must skip the override when isIidxLike() is true.
            return -1;
        }
        // NOTE: The order of the rule is from VERY-HARD to VERY-EASY:
        // VERY-HARD | HARD | NORMAL | EASY | VERY-EASY
        //     0     |  1   |   2    |  3   |     4
        // However, the order defined here is completely reversed and VERY-EASY is not an option (LR2 doesn't
        // support VERY-EASY and LR2oraja considers it as EASY directly). Therefore, we need a transformation:
        // EASY 0 -> 3 | NORMAL: 1 -> 2 | HARD: 2 -> 1 | VERY-HARD: 3 -> 0
        // We can observe that the sum is always 3
        BMSPlayerRule rule = BMSPlayerRule.getBMSPlayerRule(mode);
        return rule.judge.windowrule.judgerank[3 - judgeRank];
    }
}

