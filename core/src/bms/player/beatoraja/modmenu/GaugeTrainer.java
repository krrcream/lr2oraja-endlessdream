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
 *   slot EASY(1)   - IIDX EASY    border 80, no guts
 *   slot NORMAL(2) - IIDX NORMAL  border 80, no guts
 *   slot HARD(3)   - IIDX HARD    death 2, guts {{32, 0.6}}  (IIDX HARD:   Low Life Adj = Yes)
 *   slot EXHARD(4) - IIDX EXHARD  death 2, no guts           (IIDX EXHARD: Low Life Adj = No)
 *
 * EASY / NORMAL therefore still pass at 80%, and HARD / EXHARD still clear as long as you don't run
 * out of life - only the drain and recover values differ from the original gauges.
 *
 * All four slots are overridden rather than only the selected one, because the gauge auto shift can
 * move the active slot mid-song (including a fallback to NORMAL, see BMSPlayer STATE_FINISHED).
 * Course gauges (CLASS / EXCLASS / EXHARDCLASS) are left untouched.
 */
public class GaugeTrainer {

	/** Index into {@link #GAUGE_OPTIONS}; also the GrooveGauge slot each IIDX gauge is mapped onto. */
	public static final int IIDX_EASY = GrooveGauge.EASY;
	public static final int IIDX_NORMAL = GrooveGauge.NORMAL;
	public static final int IIDX_HARD = GrooveGauge.HARD;
	public static final int IIDX_EXHARD = GrooveGauge.EXHARD;

	public static final String[] GAUGE_OPTIONS = new String[]{
			"Default", "IIDX EASY", "IIDX NORMAL", "IIDX HARD", "IIDX EXHARD"
	};

	private static boolean active;
	private static int gaugeType = IIDX_EASY;

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean active) {
		GaugeTrainer.active = active;
	}

	public static int getGaugeType() {
		return gaugeType;
	}

	public static void setGaugeType(int gaugeType) {
		GaugeTrainer.gaugeType = gaugeType;
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
	 * @param slot EASY / NORMAL / HARD / EXHARD
	 * @param a the EASY / NORMAL recover amount
	 * @return the six gauge deltas, or null if the slot is not an IIDX gauge
	 */
	private static float[] values(int slot, float a) {
		switch (slot) {
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
	 * note is judged. Does nothing when the trainer is off, and never touches course gauges.
	 *
	 * @param gauge the play gauge, may be null
	 * @param model the model being played
	 */
	public static void apply(GrooveGauge gauge, BMSModel model) {
		if (!active || gauge == null || model == null || gauge.isCourseGauge()) {
			return;
		}
		final float a = recoverAmount(model);
		final int length = gauge.getGaugeTypeLength();
		for (int slot = IIDX_EASY; slot <= IIDX_EXHARD && slot < length; slot++) {
			final float[] delta = values(slot, a);
			if (delta != null) {
				gauge.getGauge(slot).setGaugeValues(delta);
			}
		}
		if (gaugeType >= IIDX_EASY && gaugeType <= IIDX_EXHARD && gaugeType < length) {
			gauge.setType(gaugeType);
		}
	}
}
