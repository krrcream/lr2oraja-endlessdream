package bms.player.beatoraja.modmenu;

import java.util.Arrays;

import bms.model.BMSModel;
import bms.model.LongNote;
import bms.model.MineNote;
import bms.model.Mode;
import bms.model.Note;
import bms.model.TimeLine;

/**
 * 谱面属性计算器：把 {@link BMSModel} 换算成 NOTE／PEAK／SCRATCH／SO-FLAN／CHARGE／CHORD 六轴，刻度 0–200。
 *
 * <p>口径以 note_attributes 目录里的 Python 实现为准，而不是 IIDX_Radar_7Axis.md 第 4 节：
 * 长条只算头；scratch_per_sec 的分子含长条头尾。</p>
 *
 * <p>时长取 {@link BMSModel#getLastTime()}（最后一个有数据的 TimeLine），比 Python 补到小节末尾的口径略短，
 * 因此每秒类指标会略偏高，属于已接受的近似。</p>
 *
 * <p>2026-10-31 起 CHORD／CHARGE／SO-FLAN 三条改用 7545 张官方真值重新标定的算法，
 * 并按谱面风格选 SP／DP 断点；PEAK 的窗口由 4 秒放宽到 4.18 秒。详见 IIDX_Radar_7Axis.md。</p>
 *
 * <p>第七轴 STREAM 是我们自造的（IIDX 没有这一轴），2026-10-29 按用户要求停用，算法以注释形式保留在本类里。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarCalculator {

	/** 轴数 */
	public static final int AXIS_COUNT = 6;

	/** 轴标签，顺时针从正上方开始 */
	public static final String[] AXIS_LABELS = { "NOTES", "PEAK", "SCRATCH", "SO-FLAN", "CHARGE", "CHORD" };

	/** NOTES / PEAK 的 IIDX 四段折线断点（DP；这两轴沿用原口径，不区分风格） */
	private static final double[] NOTES_BREAK = { 10.4, 15.4, 17.9, 20.0 };
	private static final double[] PEAK_BREAK = { 70.0, 113.0, 122.0, 170.0 };

	/** 后三轴的 IIDX 四段折线断点：SP / DP 各一套 */
	private static final double[] SOFLAN_BREAK_SP = { 440.0, 1000.0, 2000.0, 3800.0 };
	private static final double[] SOFLAN_BREAK_DP = { 440.0, 1000.0, 2000.0, 4000.0 };
	private static final double[] CHARGE_BREAK_SP = { 160.0, 310.0, 440.0, 900.0 };
	private static final double[] CHARGE_BREAK_DP = { 145.0, 280.0, 400.0, 750.0 };
	private static final double[] CHORD_BREAK_SP = { 500.0, 1010.0, 1200.0, 1450.0 };
	private static final double[] CHORD_BREAK_DP = { 500.0, 1000.0, 1200.0, 1500.0 };

	/** 峰值窗口：名义 4 秒，实测最优 4.18 秒（微秒） */
	private static final long PEAK_WINDOW_MICRO = 4180000L;

	/*
	 * 以下四项是已停用的 STREAM 轴参数（第七轴由我们自己定义，2026-10-29 按用户要求停用，保留备查）：
	 * 连打窗口半宽 0.75 拍换算成秒即 45 / BPM，阈值为窗口内加权每秒音符数。
	 */
	private static final double STREAM_HALF_WINDOW_NUMERATOR = 45.0;
	private static final double STREAM_NPS_THRESHOLD = 8.0;
	private static final double STREAM_WEIGHT_SINGLE = 1.0;
	private static final double STREAM_WEIGHT_CHORD = 0.6;

	/** SCRATCH 的拟合系数，来自 284 张 DP ANOTHER 的拟合结果（见 iidx_map.py 的 FIT 表） */
	private static final double SCRATCH_A = -6.968739404026607;
	private static final double SCRATCH_B = 119.25351709015858;

	/** CHORD：按和弦规模计权后除以时长，再走官方四段折线（7545 张拟合） */
	private static final double CHORD_UNIT_M2 = 116.86;
	private static final double CHORD_UNIT_M3 = 273.75;
	private static final double CHORD_UNIT_M4 = 426.46;
	private static final double CHORD_UNIT_M5P = 670.44;

	/** CHARGE：442 · h^0.88 · L^0.35，h 为长条头/秒，L 为按住时长合计/秒 */
	private static final double CHARGE_SCALE = 442.0;
	private static final double CHARGE_HEAD_EXPONENT = 0.88;
	private static final double CHARGE_HOLD_EXPONENT = 0.35;

	/** SO-FLAN：9.954 · Σ rate^0.25 · Σ(变化点后 4 秒内音符的线性衰减权重) */
	private static final double SOFLAN_SCALE = 9.954;
	private static final double SOFLAN_RATE_EXPONENT = 0.25;
	private static final long SOFLAN_WINDOW_MICRO = 4000000L;

	/** BPM 视为未变化的容差 */
	private static final double BPM_EPSILON = 1e-9;

	private ChartRadarCalculator() {
	}

	/**
	 * 这套断点与拟合只对 7 键／14 键谱面成立，其余模式（5 键、10 键、PMS、24 键）不参与计算。
	 */
	public static boolean isSupported(Mode mode) {
		return mode == Mode.BEAT_7K || mode == Mode.BEAT_14K;
	}

	/**
	 * 计算六轴数值，顺序与 {@link #AXIS_LABELS} 一致。
	 */
	public static float[] compute(BMSModel model) {
		final Mode mode = model.getMode();
		final int lanes = mode.key;
		final boolean singlePlay = mode == Mode.BEAT_7K;
		final TimeLine[] timelines = model.getAllTimeLines();

		int noteCount = 0;      // notes_per_sec：单点与长条头
		int scratchCount = 0;   // scratch_per_sec：含长条头尾
		int lnHeadCount = 0;    // 长条头（含皿），CHARGE 的 h
		long lnHoldMicros = 0;  // 长条按住时长合计，CHARGE 的 L

		// 和弦规模直方图：m2／m3／m4／m5p 分别为同刻 2／3／4／5+ 键的时刻数
		int chordM2 = 0;
		int chordM3 = 0;
		int chordM4 = 0;
		int chordM5Plus = 0;

		final long[] lnStartMicro = new long[lanes];
		for (int lane = 0; lane < lanes; lane++) {
			lnStartMicro[lane] = -1;
		}

		final LongBuffer peakTimes = new LongBuffer();
		final BpmChangeBuffer bpmChanges = new BpmChangeBuffer();
		// STREAM 停用后不再需要连打候选缓冲：final StreamBuffer streamEvents = new StreamBuffer();

		double previousBpm = model.getBpm();
		for (TimeLine timeline : timelines) {
			final long time = timeline.getMicroTime();
			final double bpm = timeline.getBPM();
			if (Math.abs(bpm - previousBpm) > BPM_EPSILON) {
				final double low = Math.min(previousBpm, bpm);
				final double high = Math.max(previousBpm, bpm);
				if (low > 0) {
					bpmChanges.add(time, (high - low) / low);
				}
				previousBpm = bpm;
			}

			// 同刻分组：去掉长条尾的那份用于和弦规模统计
			int groupHead = 0;
			for (int lane = 0; lane < lanes; lane++) {
				final Note note = timeline.getNote(lane);
				if (note == null || note instanceof MineNote) {
					continue;
				}
				if (!isLongNoteEnd(note)) {
					groupHead++;
				}
			}
			if (groupHead == 2) {
				chordM2++;
			} else if (groupHead == 3) {
				chordM3++;
			} else if (groupHead == 4) {
				chordM4++;
			} else if (groupHead > 4) {
				chordM5Plus++;
			}

			for (int lane = 0; lane < lanes; lane++) {
				final Note note = timeline.getNote(lane);
				if (note == null || note instanceof MineNote) {
					continue;
				}
				final boolean scratch = mode.isScratchKey(lane);
				final boolean lnEnd = isLongNoteEnd(note);
				if (scratch) {
					scratchCount++;
				}
				if (!lnEnd) {
					// 长条只算头：峰值、每秒音符数、每秒长条数都只看头
					noteCount++;
					peakTimes.add(time);
					if (note instanceof LongNote) {
						lnHeadCount++;
						lnStartMicro[lane] = time;
					}
				} else if (lnStartMicro[lane] >= 0 && time > lnStartMicro[lane]) {
					// 同一轨道的长条逐条累加，不合并重叠
					lnHoldMicros += time - lnStartMicro[lane];
					lnStartMicro[lane] = -1;
				}
			}
		}

		final double seconds = Math.max(model.getLastTime() / 1000.0, 0.001);

		// 4.18 秒滑动峰值：左端落在某个音符上，双指针不回退（与 axes.py 的实现一致）
		int peak = 0;
		for (int i = 0, j = 0; i < peakTimes.size; i++) {
			while (j < peakTimes.size && peakTimes.values[j] - peakTimes.values[i] <= PEAK_WINDOW_MICRO) {
				j++;
			}
			peak = Math.max(peak, j - i);
		}

		/*
		 * SO-FLAN：对每个 BPM 变化点，累计其后方 4 秒内音符的线性衰减权重，再乘变化率的 0.25 次幂。
		 * 完全不变速时直接给 0，不经过折线，避免拟合残留常数把 0 变成非 0。
		 */
		double soflan = 0.0;
		if (bpmChanges.size > 0) {
			for (int c = 0; c < bpmChanges.size; c++) {
				final long changeTime = bpmChanges.times[c];
				final int from = upperBound(peakTimes.values, peakTimes.size, changeTime);
				final int to = upperBound(peakTimes.values, peakTimes.size, changeTime + SOFLAN_WINDOW_MICRO);
				double weightSum = 0.0;
				for (int i = from; i < to; i++) {
					weightSum += 1.0 - (double) (peakTimes.values[i] - changeTime) / SOFLAN_WINDOW_MICRO;
				}
				if (weightSum > 0) {
					soflan += Math.pow(bpmChanges.rates[c], SOFLAN_RATE_EXPONENT) * weightSum;
				}
			}
			soflan *= SOFLAN_SCALE;
		}

		/*
		 * STREAM 轴已停用，原连打比率计算保留备查：
		 * 候选为全部非皿事件（含长条尾），权重为同刻事件数 >= 2 时取 STREAM_WEIGHT_CHORD 否则取 SINGLE；
		 * 对每个候选取 ±(45 / 局部BPM) 秒窗口，窗口内加权和 / 窗口全宽 >= 8 计一个候选；
		 * x_stream = 候选数 / 全部事件数，radar[6] = clamp(x_stream * 200)。
		 */

		final float[] radar = new float[AXIS_COUNT];
		radar[0] = (float) iidxRadar(noteCount / seconds, NOTES_BREAK);
		radar[1] = (float) iidxRadar(peak, PEAK_BREAK);
		radar[2] = (float) clamp(SCRATCH_A + SCRATCH_B * Math.log1p(scratchCount / seconds));
		radar[3] = bpmChanges.size == 0 ? 0.0f
				: (float) iidxRadar(soflan, singlePlay ? SOFLAN_BREAK_SP : SOFLAN_BREAK_DP);

		// CHARGE：没有长条时直接给 0
		if (lnHeadCount == 0 || lnHoldMicros <= 0) {
			radar[4] = 0.0f;
		} else {
			final double headsPerSecond = lnHeadCount / seconds;
			final double holdPerSecond = (lnHoldMicros / 1000000.0) / seconds;
			final double chargeRaw = CHARGE_SCALE
					* Math.pow(headsPerSecond, CHARGE_HEAD_EXPONENT)
					* Math.pow(holdPerSecond, CHARGE_HOLD_EXPONENT);
			radar[4] = (float) iidxRadar(chargeRaw, singlePlay ? CHARGE_BREAK_SP : CHARGE_BREAK_DP);
		}

		final double chordRaw = CHORD_UNIT_M2 * chordM2 + CHORD_UNIT_M3 * chordM3
				+ CHORD_UNIT_M4 * chordM4 + CHORD_UNIT_M5P * chordM5Plus;
		radar[5] = (float) iidxRadar(chordRaw / seconds, singlePlay ? CHORD_BREAK_SP : CHORD_BREAK_DP);
		return radar;
	}

	/** 长条尾判定：MINE 之外的 LongNote 且 isEnd() 为真 */
	private static boolean isLongNoteEnd(Note note) {
		return note instanceof LongNote && ((LongNote) note).isEnd();
	}

	/** IIDX 的四段折线：段末值 1.0 / 1.5 / 1.75 / 2.0，乘 100 得到 0–200 */
	private static double iidxRadar(double x, double[] points) {
		final double p0 = points[0];
		final double p1 = points[1];
		final double p2 = points[2];
		final double p3 = points[3];
		final double r;
		if (x <= p0) {
			r = clamp(x / p0, 0.0, 1.0);
		} else if (x <= p1) {
			r = 1.0 + 0.5 * (x - p0) / (p1 - p0);
		} else if (x <= p2) {
			r = 1.5 + 0.25 * (x - p1) / (p2 - p1);
		} else {
			r = 1.75 + 0.25 * clamp((x - p2) / (p3 - p2), 0.0, 1.0);
		}
		return r * 100.0;
	}

	private static double clamp(double value) {
		return clamp(value, 0.0, 200.0);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/*
	 * 以下两个二分查找：upperBound 用于 SO-FLAN 的窗口取音符，lowerBound 目前只服务于已停用的 STREAM 轴。
	 */
	private static int lowerBound(long[] values, int size, long target) {
		int low = 0;
		int high = size;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			if (values[middle] < target) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return low;
	}

	private static int upperBound(long[] values, int size, long target) {
		int low = 0;
		int high = size;
		while (low < high) {
			final int middle = (low + high) >>> 1;
			if (values[middle] <= target) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return low;
	}

	/** 峰值用的时间轴缓冲，只存长条头与单点 */
	private static final class LongBuffer {

		private long[] values = new long[512];
		private int size;

		private void add(long value) {
			if (size == values.length) {
				values = Arrays.copyOf(values, size * 2);
			}
			values[size++] = value;
		}
	}

	/** BPM 变化点缓冲：时间与变化率，供 SO-FLAN 使用 */
	private static final class BpmChangeBuffer {

		private long[] times = new long[64];
		private double[] rates = new double[64];
		private int size;

		private void add(long time, double rate) {
			if (size == times.length) {
				times = Arrays.copyOf(times, size * 2);
				rates = Arrays.copyOf(rates, size * 2);
			}
			times[size] = time;
			rates[size] = rate;
			size++;
		}
	}

	/** 连打候选缓冲：时间、局部 BPM、权重。STREAM 轴停用后暂时没有调用方，保留备查。 */
	private static final class StreamBuffer {

		private long[] times = new long[512];
		private double[] bpms = new double[512];
		private double[] weights = new double[512];
		private int size;

		private void add(long time, double bpm, double weight) {
			if (size == times.length) {
				times = Arrays.copyOf(times, size * 2);
				bpms = Arrays.copyOf(bpms, size * 2);
				weights = Arrays.copyOf(weights, size * 2);
			}
			times[size] = time;
			bpms[size] = bpm;
			weights[size] = weight;
			size++;
		}
	}
}
