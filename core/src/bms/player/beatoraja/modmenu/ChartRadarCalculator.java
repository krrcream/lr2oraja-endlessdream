package bms.player.beatoraja.modmenu;

import java.util.Arrays;

import bms.model.BMSModel;
import bms.model.LongNote;
import bms.model.MineNote;
import bms.model.Mode;
import bms.model.Note;
import bms.model.TimeLine;

/**
 * 谱面七维属性计算器：把 {@link BMSModel} 换算成 NOTE／PEAK／SCRATCH／SO-FLAN／CHARGE／CHORD／STREAM 七轴，刻度 0–200。
 *
 * <p>口径以 note_attributes 目录里的 Python 实现为准，而不是 IIDX_Radar_7Axis.md 第 4 节：
 * 长条只算头；scratch_per_sec 的分子含长条头尾；连打比率的候选含长条尾、同刻事件数含皿。</p>
 *
 * <p>时长取 {@link BMSModel#getLastTime()}（最后一个有数据的 TimeLine），比 Python 补到小节末尾的口径略短，
 * 因此每秒类指标会略偏高，属于已接受的近似。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarCalculator {

	/** 轴数 */
	public static final int AXIS_COUNT = 7;

	/** 轴标签，顺时针从正上方开始 */
	public static final String[] AXIS_LABELS = { "NOTE", "PEAK", "SCRATCH", "SO-FLAN", "CHARGE", "CHORD", "STREAM" };

	/** IIDX 四段折线的断点（DP） */
	private static final double[] NOTES_BREAK = { 10.4, 15.4, 17.9, 20.0 };
	private static final double[] PEAK_BREAK = { 70.0, 113.0, 122.0, 170.0 };

	/** 4 秒峰值窗口（微秒） */
	private static final long PEAK_WINDOW_MICRO = 4000000L;

	/** 连打窗口半宽为 0.75 拍，换算成秒即 45 / BPM */
	private static final double STREAM_HALF_WINDOW_NUMERATOR = 45.0;
	/** 连打判定阈值：窗口内加权每秒音符数达到该值才算候选 */
	private static final double STREAM_NPS_THRESHOLD = 8.0;
	/** 连打权重：单点与和弦 */
	private static final double STREAM_WEIGHT_SINGLE = 1.0;
	private static final double STREAM_WEIGHT_CHORD = 0.6;

	/** 四条拟合轴的系数，来自 284 张 DP ANOTHER 的拟合结果（见 iidx_map.py 的 FIT 表） */
	private static final double SCRATCH_A = -6.968739404026607;
	private static final double SCRATCH_B = 119.25351709015858;
	private static final double CHORD_A = -33.70389651658037;
	private static final double CHORD_B = 72.5274098806752;
	private static final double CHARGE_A = -2.7879015241802705;
	private static final double CHARGE_B = 170.35467959271412;
	private static final double SOFLAN_A = 1.1735839585024657;
	private static final double SOFLAN_B = 52.84549408943682;

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
	 * 计算七轴数值，顺序与 {@link #AXIS_LABELS} 一致。
	 */
	public static float[] compute(BMSModel model) {
		final Mode mode = model.getMode();
		final int lanes = mode.key;
		final TimeLine[] timelines = model.getAllTimeLines();

		int noteCount = 0;      // notes_per_sec：单点与长条头
		int scratchCount = 0;   // scratch_per_sec：含长条头尾
		int lnHeadCount = 0;    // ln_per_sec：全部长条头（含皿）
		int pairCount = 0;      // pairs_per_sec：n 键和弦贡献 C(n,2)
		int eventCount = 0;     // 连打比率的分母：全部事件
		int bpmChanges = 0;

		final LongBuffer peakTimes = new LongBuffer();
		final StreamBuffer streamEvents = new StreamBuffer();

		double previousBpm = model.getBpm();
		for (TimeLine timeline : timelines) {
			final long time = timeline.getMicroTime();
			final double bpm = timeline.getBPM();
			if (Math.abs(bpm - previousBpm) > BPM_EPSILON) {
				bpmChanges++;
				previousBpm = bpm;
			}

			// 同刻分组：全体事件（含皿、含长条尾）用于连打权重，去掉长条尾的那份用于同时押对数
			int groupAll = 0;
			int groupHead = 0;
			for (int lane = 0; lane < lanes; lane++) {
				final Note note = timeline.getNote(lane);
				if (note == null || note instanceof MineNote) {
					continue;
				}
				groupAll++;
				if (!isLongNoteEnd(note)) {
					groupHead++;
				}
			}
			if (groupHead > 1) {
				pairCount += groupHead * (groupHead - 1) / 2;
			}
			final double weight = groupAll >= 2 ? STREAM_WEIGHT_CHORD : STREAM_WEIGHT_SINGLE;

			for (int lane = 0; lane < lanes; lane++) {
				final Note note = timeline.getNote(lane);
				if (note == null || note instanceof MineNote) {
					continue;
				}
				final boolean scratch = mode.isScratchKey(lane);
				final boolean lnEnd = isLongNoteEnd(note);
				eventCount++;
				if (scratch) {
					scratchCount++;
				}
				if (!lnEnd) {
					// 长条只算头：峰值、每秒音符数、每秒长条数都只看头
					noteCount++;
					peakTimes.add(time);
					if (note instanceof LongNote) {
						lnHeadCount++;
					}
				}
				if (!scratch) {
					// 连打候选含长条尾，只排除皿
					streamEvents.add(time, bpm, weight);
				}
			}
		}

		final double seconds = Math.max(model.getLastTime() / 1000.0, 0.001);

		// 4 秒滑动峰值：左端落在某个音符上，双指针不回退（与 axes.py 的实现一致）
		int peak = 0;
		for (int i = 0, j = 0; i < peakTimes.size; i++) {
			while (j < peakTimes.size && peakTimes.values[j] - peakTimes.values[i] <= PEAK_WINDOW_MICRO) {
				j++;
			}
			peak = Math.max(peak, j - i);
		}

		// 连打比率：窗口半宽取该音符处的局部 BPM，加权和除以窗口全宽得到加权 NPS
		double streamRatio = 0.0;
		if (eventCount > 0 && streamEvents.size > 0) {
			final double[] prefix = new double[streamEvents.size + 1];
			for (int i = 0; i < streamEvents.size; i++) {
				prefix[i + 1] = prefix[i] + streamEvents.weights[i];
			}
			int candidates = 0;
			for (int i = 0; i < streamEvents.size; i++) {
				final double bpm = streamEvents.bpms[i];
				if (bpm <= 0) {
					continue;
				}
				final double halfWindow = STREAM_HALF_WINDOW_NUMERATOR / bpm;
				final long halfWindowMicro = Math.round(halfWindow * 1000000.0);
				final int from = lowerBound(streamEvents.times, streamEvents.size, streamEvents.times[i] - halfWindowMicro);
				final int to = upperBound(streamEvents.times, streamEvents.size, streamEvents.times[i] + halfWindowMicro);
				final double weightSum = prefix[to] - prefix[from];
				if (weightSum / (2.0 * halfWindow) >= STREAM_NPS_THRESHOLD) {
					candidates++;
				}
			}
			streamRatio = (double) candidates / eventCount;
		}

		final float[] radar = new float[AXIS_COUNT];
		radar[0] = (float) iidxRadar(noteCount / seconds, NOTES_BREAK);
		radar[1] = (float) iidxRadar(peak, PEAK_BREAK);
		radar[2] = (float) clamp(SCRATCH_A + SCRATCH_B * Math.log1p(scratchCount / seconds));
		radar[3] = (float) clamp(SOFLAN_A + SOFLAN_B * Math.log1p(bpmChanges));
		radar[4] = (float) clamp(CHARGE_A + CHARGE_B * Math.sqrt(lnHeadCount / seconds));
		radar[5] = (float) clamp(CHORD_A + CHORD_B * Math.log1p(pairCount / seconds));
		radar[6] = (float) clamp(streamRatio * 200.0);
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

	/** 4 秒峰值用的时间轴缓冲，只存长条头与单点 */
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

	/** 连打候选缓冲：时间、局部 BPM、权重 */
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
