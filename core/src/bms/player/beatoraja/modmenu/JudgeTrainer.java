package bms.player.beatoraja.modmenu;

import bms.model.Mode;
import bms.player.beatoraja.play.BMSPlayerRule;

public class JudgeTrainer {
    public static final String[] JUDGE_OPTIONS = new String[]{
            "EASY", "NORMAL", "HARD", "VERY_HARD", "IIDX-Like", "Beatoraja Easy", "Beatoraja Normal"
    };
    /** IIDX-Like 在 {@link #JUDGE_OPTIONS} 中的下标。 */
    public static final int IIDX_LIKE_INDEX = 4;
    /** Beatoraja Easy 在 {@link #JUDGE_OPTIONS} 中的下标。 */
    public static final int BEATORAJA_EASY_INDEX = 5;
    /** Beatoraja Normal 在 {@link #JUDGE_OPTIONS} 中的下标。 */
    public static final int BEATORAJA_NORMAL_INDEX = 6;

    // IIDX（大多数谱面）的判定窗口，单位微秒，顺序为 PGREAT、GREAT、GOOD、BAD。
    // 取值来自 iidx.org 的 1 / 2 / 7 / 15 帧 @60fps，即 16666.67 / 33333.33 / 116666.67 / 250000 微秒，
    // 四舍五入到最近的整数微秒。
    // 只保留 4 行：长条松手判定要求正好 4 行（LR2 也是这个行数）。
    public static final long[][] IIDX_WINDOW = new long[][]{
            {-16667, 16667}, {-33333, 33333}, {-116667, 116667}, {-250000, 250000}
    };
    // 5 行：单键 / 长条按下判定，下标 4 是过度 POOR（空 POOR）窗口。
    // iidx.org 没有给出 IIDX 的精确值，这里取 ±333 毫秒近似：早空 POOR 落在 (250 毫秒, 333 毫秒]，
    // 250 毫秒及更早仍先命中 BAD；晚空 POOR 由同轨道对已消费音符的二次按键触发。
    public static final long[][] IIDX_NOTE = new long[][]{
            IIDX_WINDOW[0], IIDX_WINDOW[1], IIDX_WINDOW[2], IIDX_WINDOW[3], {-333000, 333000}
    };

    // Beatoraja 的 EASY / NORMAL 判定窗口，单位微秒，顺序为 PGREAT、GREAT、GOOD、BAD。
    // BAD 早晚不对称，所以每行都写成 {晚, 早}。
    // 只保留 4 行：长条松手判定要求正好 4 行（LR2 也是这个行数）。
    public static final long[][] BEATORAJA_EASY_WINDOW = new long[][]{
            {-20000, 20000}, {-60000, 60000}, {-150000, 150000}, {-280000, 220000}
    };
    public static final long[][] BEATORAJA_NORMAL_WINDOW = new long[][]{
            {-15000, 15000}, {-45000, 45000}, {-112000, 112000}, {-210000, 165000}
    };
    // 5 行：单键 / 长条按下判定，下标 4 是过度 POOR（空POOR）窗口。
    // Beatoraja 的空POOR 与 IIDX 不同，是早晚不对称的 -150000 ~ +500000。
    public static final long[][] BEATORAJA_EASY_NOTE = new long[][]{
            BEATORAJA_EASY_WINDOW[0], BEATORAJA_EASY_WINDOW[1], BEATORAJA_EASY_WINDOW[2], BEATORAJA_EASY_WINDOW[3], {-150000, 500000}
    };
    public static final long[][] BEATORAJA_NORMAL_NOTE = new long[][]{
            BEATORAJA_NORMAL_WINDOW[0], BEATORAJA_NORMAL_WINDOW[1], BEATORAJA_NORMAL_WINDOW[2], BEATORAJA_NORMAL_WINDOW[3], {-150000, 500000}
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
     * IIDX-Like does not go through the judge rank percentage scaling at all; it replaces the
     * materialized judge windows directly in JudgeManager.
     */
    public static boolean isIidxLike() {
        return active && judgeRank == IIDX_LIKE_INDEX;
    }

    /**
     * Beatoraja Easy / Normal 与 IIDX-Like 一样不经过判定等级的百分比缩放，直接在 JudgeManager 里替换判定窗口。
     */
    public static boolean isBeatorajaJudge() {
        return active && (judgeRank == BEATORAJA_EASY_INDEX || judgeRank == BEATORAJA_NORMAL_INDEX);
    }

    /**
     * 是否属于「直接覆盖判定窗口」的选项（IIDX-Like / Beatoraja Easy / Beatoraja Normal）。
     */
    public static boolean isDirectOverride() {
        return active && judgeRank >= IIDX_LIKE_INDEX;
    }

    /**
     * 单键 / 长条按下判定要直接使用的窗口表；非直接覆盖的选项返回 null，调用方应保持原值。
     */
    public static long[][] getDirectNoteWindow() {
        if (!active) {
            return null;
        }
        if (judgeRank == IIDX_LIKE_INDEX) {
            return IIDX_NOTE;
        } else if (judgeRank == BEATORAJA_EASY_INDEX) {
            return BEATORAJA_EASY_NOTE;
        } else if (judgeRank == BEATORAJA_NORMAL_INDEX) {
            return BEATORAJA_NORMAL_NOTE;
        }
        return null;
    }

    /**
     * 长条松手判定要直接使用的窗口表；非直接覆盖的选项返回 null，调用方应保持原值。
     */
    public static long[][] getDirectReleaseWindow() {
        if (!active) {
            return null;
        }
        if (judgeRank == IIDX_LIKE_INDEX) {
            return IIDX_WINDOW;
        } else if (judgeRank == BEATORAJA_EASY_INDEX) {
            return BEATORAJA_EASY_WINDOW;
        } else if (judgeRank == BEATORAJA_NORMAL_INDEX) {
            return BEATORAJA_NORMAL_WINDOW;
        }
        return null;
    }

    public static int getJudgeWindowRate(Mode mode) {
        if (isDirectOverride()) {
            // Not a valid judge window rate; caller must skip the override when isDirectOverride() is true.
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

