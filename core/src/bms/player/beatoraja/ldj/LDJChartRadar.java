package bms.player.beatoraja.ldj;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bms.model.BMSModel;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.modmenu.ChartRadarModelCache;
import bms.player.beatoraja.song.SongData;

/**
 * Chart Radar 与 LDJ 的桥接：让 LDJ 歌单里只要有转换产物的曲目也能出雷达。
 *
 * <p>ChartRadar 自己不认识 LDJ，全靠 {@link ChartRadarModelCache#setModelResolver} 这个静态入口。
 * 这里把「iidx:// 路径 → 转换缓存里的 BMS」接上去，只用已有缓存，不触发新的转换。</p>
 *
 * <p>读缓存与解码都放在后台线程：解析器每帧都会被问一次，没算好就返回 null，由雷达画面显示空图，
 * 算完下一次调用就有值了（与选曲画面回填谱面统计的口径一致）。</p>
 *
 * @author endlessdream
 */
public final class LDJChartRadar {

	private static final Logger logger = LoggerFactory.getLogger(LDJChartRadar.class);

	private static final Object LOCK = new Object();

	/** 只缓存最近一首，避免把整套 LDJ 谱面常驻内存；null 也是有效结果（表示没有可用缓存） */
	private static String cachedPath;
	private static BMSModel cachedModel;
	private static String pendingPath;

	private static MainController main;
	/** 延迟创建：注入时 playerResource 可能还没准备好 */
	private static LDJConversionService converter;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable runnable) {
			final Thread thread = new Thread(runnable, "ldj-chart-radar");
			thread.setDaemon(true);
			return thread;
		}
	});

	private LDJChartRadar() {
	}

	/** 由 MainController 注入，并替换掉 ChartRadar 的谱面来源解析 */
	public static void init(MainController mainController) {
		if (mainController == null) {
			return;
		}
		main = mainController;
		ChartRadarModelCache.setModelResolver(new Resolver());
	}

	private static LDJConversionService converter() {
		synchronized (LOCK) {
			if (converter == null && main != null) {
				final Config config = main.getPlayerResource().getConfig();
				final String sourcesRoot = config.getLDJ_Sources();
				final LDJTempFileManager tempFiles = (sourcesRoot == null || sourcesRoot.isEmpty())
						? new LDJTempFileManager()
						: new LDJTempFileManager(Paths.get(sourcesRoot));
				if (tempFiles.prepare()) {
					converter = new LDJConversionService(tempFiles);
				}
			}
			return converter;
		}
	}

	private static final class Resolver implements ChartRadarModelCache.ModelResolver {

		@Override
		public BMSModel resolve(SongData song) {
			if (song == null) {
				return null;
			}
			// 非 LDJ 曲目照旧用自带的谱面模型，行为与 dev 侧默认解析完全一致
			if (!song.isLDJChart() || !LDJSongProvider.isLDJPath(song.getPath()) || converter() == null) {
				return song.getBMSModel();
			}
			return resolveCached(song.getPath());
		}
	}

	private static BMSModel resolveCached(String path) {
		synchronized (LOCK) {
			if (path.equals(cachedPath)) {
				return cachedModel;
			}
			if (path.equals(pendingPath)) {
				return null;
			}
			pendingPath = path;
		}
		EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				final BMSModel model = loadCachedModel(path);
				synchronized (LOCK) {
					cachedPath = path;
					cachedModel = model;
					pendingPath = null;
				}
			}
		});
		return null;
	}

	/** 读转换缓存里的谱面，没有可用缓存时返回 null（不触发转换） */
	private static BMSModel loadCachedModel(String path) {
		final LDJChartRef ref = LDJChartRef.parse(path);
		final LDJConversionService service = converter();
		if (ref == null || service == null || main == null) {
			return null;
		}
		try {
			final LDJConversionService.Result cached = service.readCachedResult(newRequest(ref));
			if (cached == null) {
				return null;
			}
			final String token = ref.getDifficulty().getToken();
			for (LDJConversionService.Chart chart : cached.charts) {
				if (token.equalsIgnoreCase(chart.getDifficulty())) {
					final Path file = chart.resolve(cached.directory);
					return main.getPlayerResource().loadBMSModel(file, main.getPlayerConfig().getLnmode());
				}
			}
		} catch (LDJConversionService.ConversionException e) {
			logger.warn("iidx2bms: レーダー用の譜面読み込みに失敗しました: {}", e.getMessage());
		}
		return null;
	}

	/** outRoot 传 null：只查缓存，不做转换 */
	private static LDJConversionService.Request newRequest(LDJChartRef ref) {
		final Config ldj = main.getPlayerResource().getConfig();
		return new LDJConversionService.Request(ref.getSongId(), ref.getSongIdDisplay(),
				ldj.getLDJ_ToolPath(), ldj.getLDJ_SoundPath(), ldj.getLDJ_MoviePath(), null);
	}
}
