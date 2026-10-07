package bms.player.beatoraja.modmenu;

import bms.model.Mode;
import bms.player.beatoraja.play.BMSPlayerRule;

public class JudgeTrainer {
    public static final String[] JUDGE_OPTIONS = new String[]{
            "EASY", "NORMAL", "HARD", "VERY_HARD", "IIDX-Like Type"
    };
    public static final int IIDX_LIKE_INDEX = JUDGE_OPTIONS.length - 1;

    // IIDX (most charts) windows, rounded to whole milliseconds from iidx.org's
    // 16.67 / 33.33 / 116.67 / 250 ms. Order is PGREAT, GREAT, GOOD, BAD.
    // 4 rows only: long note release judging expects exactly 4 rows (LR2 does the same).
    public static final long[][] IIDX_WINDOW = new long[][]{
            {-17000, 17000}, {-33000, 33000}, {-117000, 117000}, {-250000, 250000}
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

