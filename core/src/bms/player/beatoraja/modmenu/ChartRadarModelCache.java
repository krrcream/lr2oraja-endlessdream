package bms.player.beatoraja.modmenu;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import bms.model.BMSModel;
import bms.player.beatoraja.song.SongData;

/**
 * 七维结果缓存：按 BMSModel 身份判断是否重算，重算跑在后台线程，渲染线程只读已经算好的结果。
 *
 * <p>谱面来源默认直接取 {@link SongData#getBMSModel()}。LDJ 侧可以把 {@link ModelResolver} 换成走转换缓存的实现
 * （LDJPlus 上的 LDJChartRadar），ChartRadar 自身不引用任何 LDJ 类，两边只在这一个静态入口交汇。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarModelCache {

	/**
	 * 谱面来源解析，默认取 SongData 自带的模型。
	 */
	public interface ModelResolver {
		BMSModel resolve(SongData song);
	}

	private static final Object LOCK = new Object();

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			final Thread thread = new Thread(runnable, "chart-radar");
			thread.setDaemon(true);
			return thread;
		}
	});

	private static volatile ModelResolver resolver = new ModelResolver() {
		@Override
		public BMSModel resolve(SongData song) {
			return song != null ? song.getBMSModel() : null;
		}
	};

	private static BMSModel currentModel;
	private static float[] currentValues;
	private static BMSModel pendingModel;

	private ChartRadarModelCache() {
	}

	/**
	 * 装配谱面来源解析。LDJ 侧的桥接类在初始化时调用，并清掉已有缓存。
	 */
	public static void setModelResolver(ModelResolver modelResolver) {
		if (modelResolver == null) {
			return;
		}
		resolver = modelResolver;
		synchronized (LOCK) {
			currentModel = null;
			currentValues = null;
			pendingModel = null;
		}
	}

	/**
	 * 取当前曲目的七维结果。尚未算完、谱面读不到或模式不支持时返回 null，调用方按空图处理，下一帧再取。
	 */
	public static float[] get(SongData song) {
		final BMSModel model = resolver.resolve(song);
		if (model == null) {
			return null;
		}
		synchronized (LOCK) {
			if (model == currentModel) {
				return currentValues;
			}
			if (model == pendingModel) {
				return null;
			}
			pendingModel = model;
		}
		EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				float[] values = null;
				try {
					if (ChartRadarCalculator.isSupported(model.getMode())) {
						values = ChartRadarCalculator.compute(model);
					}
				} catch (RuntimeException e) {
					System.err.println("[chart-radar] failed to compute radar: " + e.getMessage());
				}
				synchronized (LOCK) {
					currentModel = model;
					currentValues = values;
					pendingModel = null;
				}
			}
		});
		return null;
	}
}
