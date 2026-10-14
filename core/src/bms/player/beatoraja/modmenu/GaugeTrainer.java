package bms.player.beatoraja.modmenu;

import bms.model.BMSModel;
import bms.player.beatoraja.play.GrooveGauge;

/**
 * Replaces the groove gauge's drain / recover amounts with IIDX's, keeping beatoraja's own clear
 * conditions. Numbers are taken from https://iidx.org/misc/iidx_lr2_beatoraja_diff
 * (Gauge types table: PG, GREAT, GOOD, BAD, POOR, 空POOR).
 *
 * Each IIDX gauge is mapped onto the beatoraja slot that already means the same thing, so border,
 * death and guts are inherited and only the numbers change:
 *
 *   slot ASSISTEASY(0) - IIDX A-EASY  border 60, no guts
 *   slot EASY(1)       - IIDX EASY    border 80, no guts
 *   slot NORMAL(2)     - IIDX NORMAL  border 80, no guts
 *   slot HARD(3)       - IIDX HARD    death 2, guts {{30, 0.5}}  (IIDX HARD:   Low Life Adj = Yes)
 *   slot EXHARD(4)     - IIDX EXHARD  death 2, no guts           (IIDX EXHARD: Low Life Adj = No)
 *
 * A-EASY / EASY / NORMAL therefore still pass at 60% / 80%, and HARD / EXHARD still clear as long as
 * you don't run out of life - only the drain and recover values differ from the original gauges.
 *
 * Every slot is opt-in and independent: a slot is only overridden while its own switch is on, so the
 * play gauge keeps the type the config or the chart gave it and beatoraja classifies the result as
 * usual. All enabled slots are written at once rather than only the active one, because the gauge
 * auto shift can move the active slot mid-song (including a fallback to NORMAL, see BMSPlayer
 * STATE_FINISHED). Course gauges (CLASS / EXCLASS / EXHARDCLASS) are left untouched.
 */
public class GaugeTrainer {

	/** GrooveGauge 的槽位编号，顺序与 IIDX 血条的排列一致。 */
	public static final int IIDX_AEASY = GrooveGauge.ASSISTEASY;
	public static final int IIDX_EASY = GrooveGauge.EASY;
	public static final int IIDX_NORMAL = GrooveGauge.NORMAL;
	public static final int IIDX_HARD = GrooveGauge.HARD;
	public static final int IIDX_EXHARD = GrooveGauge.EXHARD;

	/** IIDX 血条的个数，同时也是槽位编号的取值个数。 */
	public static final int GAUGE_COUNT = 5;

	/**
	 * 各 IIDX 血条的开关，下标即 GrooveGauge 的槽位编号（0 = A-EASY … 4 = EX-HARD）。
	 */
	private static final boolean[] ENABLED = new boolean[GAUGE_COUNT];

	/**
	 * 读取指定槽位的 IIDX 血条是否启用。
	 *
	 * @param slot GrooveGauge 的槽位编号
	 * @return 槽位越界时返回 false
	 */
	public static boolean isEnabled(int slot) {
		return slot >= 0 && slot < ENABLED.length && ENABLED[slot];
	}

	/**
	 * 设置指定槽位的 IIDX 血条开关。
	 *
	 * @param slot GrooveGauge 的槽位编号
	 * @param enabled 是否启用
	 */
	public static void setEnabled(int slot, boolean enabled) {
		if (slot >= 0 && slot < ENABLED.length) {
			ENABLED[slot] = enabled;
		}
	}

	/**
	 * IIDX 血条的最低生命值补正：只有 HARD 需要（生命值低于 30 时扣血减半），
	 * 其余槽位返回 null，表示沿用槽位自带的设置。
	 */
	private static float[][] guts(int slot) {
		return slot == IIDX_HARD ? new float[][]{{30f, 0.5f}} : null;
	}

	/**
	 * IIDX's gauge recover amount per note, as a function of the chart's note count.
	 * Great recovers the same and good recovers half of it.
	 */
	private static float recoverAmount(BMSModel model) {
		final float notes = model.getTotalNotes();
		if (notes <= 0) {
			return 0f;
		}
		return notes <= 338 ? 260f / notes : 760.5f / (notes + 650f);
	}

	/**
	 * Values for one gauge slot, already scaled to percent of the gauge.
	 *
	 * @param slot A-EASY / EASY / NORMAL / HARD / EXHARD
	 * @param a the A-EASY / EASY / NORMAL recover amount
	 * @return the six gauge deltas, or null if the slot is not an IIDX gauge
	 */
	private static float[] values(int slot, float a) {
		switch (slot) {
		case IIDX_AEASY:
		case IIDX_EASY:
			return new float[]{a, a, a * 0.5f, -1.6f, -4.8f, -1.6f};
		case IIDX_NORMAL:
			return new float[]{a, a, a * 0.5f, -2f, -6f, -2f};
		case IIDX_HARD:
			return new float[]{0.16f, 0.16f, 0f, -5f, -9f, -5f};
		case IIDX_EXHARD:
			return new float[]{0.16f, 0.16f, 0f, -10f, -18f, -10f};
		default:
			return null;
		}
	}

	/**
	 * Overrides the gauge just created for this play. Call after the gauge is built and before any
	 * note is judged. Only the slots whose switch is on are written, and course gauges are never
	 * touched.
	 *
	 * @param gauge the play gauge, may be null
	 * @param model the model being played
	 */
	public static void apply(GrooveGauge gauge, BMSModel model) {
		if (gauge == null || model == null || gauge.isCourseGauge()) {
			return;
		}
		final float a = recoverAmount(model);
		final int length = gauge.getGaugeTypeLength();
		for (int slot = IIDX_AEASY; slot <= IIDX_EXHARD && slot < length; slot++) {
			if (!isEnabled(slot)) {
				continue;
			}
			final float[] delta = values(slot, a);
			if (delta != null) {
				gauge.getGauge(slot).setGaugeValues(delta);
				final float[][] guts = guts(slot);
				if (guts != null) {
					gauge.getGauge(slot).setGuts(guts);
				}
			}
		}
	}
}
