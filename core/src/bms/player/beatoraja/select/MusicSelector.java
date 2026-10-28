package bms.player.beatoraja.select;

import static bms.player.beatoraja.skin.SkinProperty.*;
import static bms.player.beatoraja.SystemSoundManager.SoundType.*;

import java.io.IOException;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import bms.player.beatoraja.modmenu.ImGuiNotify;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.*;

import bms.model.BMSModel;
import bms.model.Mode;
import bms.player.beatoraja.*;
import bms.player.beatoraja.Config.SongPreview;
import bms.player.beatoraja.ScoreDatabaseAccessor.ScoreDataCollector;
import bms.player.beatoraja.ldj.LDJChartRef;
import bms.player.beatoraja.ldj.LDJConversionOverlay;
import bms.player.beatoraja.ldj.LDJConversionService;
import bms.player.beatoraja.ldj.LDJSongProvider;
import bms.player.beatoraja.ldj.LDJTempFileManager;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyCommand;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.ir.*;
import bms.player.beatoraja.select.bar.*;
import bms.player.beatoraja.skin.SkinType;
import bms.player.beatoraja.skin.property.EventFactory.EventType;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongInformation;
import bms.player.beatoraja.song.SongDatabaseAccessor;
import imgui.ImGui;

/**
 * 選曲部分。 楽曲一覧とカーソルが指す楽曲のステータスを表示し、選択した楽曲を 曲決定部分に渡す。
 *
 * @author exch
 */
public final class MusicSelector extends MainState {
	private static final Logger logger = LoggerFactory.getLogger(MusicSelector.class);

	// TODO　ミラーランダム段位のスコア表示

	private int selectedreplay;

	/**
	 * 楽曲DBアクセサ
	 */
	private SongDatabaseAccessor songdb;

	public static final Mode[] MODE = { null, Mode.BEAT_7K, Mode.BEAT_14K, Mode.POPN_9K, Mode.BEAT_5K, Mode.BEAT_10K, Mode.KEYBOARD_24K, Mode.KEYBOARD_24K_DOUBLE };

	/**
	 * 保存可能な最大リプレイ数
	 */
	public static final int REPLAY = 4;

	private PlayerConfig config;

	/**
	 * 楽曲プレビュー処理
	 */
	private PreviewMusicProcessor preview;

	/**
	 * 楽曲バー描画用
	 */
	private BarRenderer bar;
	
	private final BarManager manager = new BarManager(this);
	
	private MusicSelectInputProcessor musicinput;

	private SearchTextField search;

	/**
	 * 楽曲が選択されてからbmsを読み込むまでの時間(ms)
	 */
	private final int notesGraphDuration = 350;
	/**
	 * 楽曲が選択されてからプレビュー曲を再生するまでの時間(ms)
	 */
	private final int previewDuration = 400;
	
	private final int rankingDuration = 5000;
	private final int rankingReloadDuration = 10 * 60 * 1000;
	
	private long currentRankingDuration = -1;

	private boolean showNoteGraph = false;

	private ScoreDataCache scorecache;

	private RankingData currentir;
	/**
	 * ランキング表示位置
	 */
	protected int rankingOffset = 0;

	private PlayerInformation rival;
	
	private int panelstate;

	private BMSPlayerMode play = null;

	private SongData playedsong = null;
	private CourseData playedcourse = null;

	private PixmapResourcePool banners;

	private PixmapResourcePool stagefiles;

	// LDJ 联动(iidx2bms)，仅在 config 中启用且转换环境可用时才非 null
	private LDJSongProvider ldjProvider;
	private LDJTempFileManager ldjTempFiles;
	private LDJConversionService ldjConverter;
	private Bar ldjBar;
	// 转换线程相关，完成检测通过 render() 中的 isAlive() 轮询
	private Thread ldjThread;
	private SongData ldjSong;
	private Bar ldjBarAtStart;
	private BMSPlayerMode ldjMode;
	// isAlive() 进行的完成检测不保证 happens-before，因此 worker 写入的字段设为 volatile
	private volatile LDJConversionService.Result ldjResult;
	private volatile String ldjError;
	// 转换成功也可能带警告(例如走了目录回退)，因此与 ldjError 分开保存
	private volatile String ldjWarning;
	private boolean ldjProceedRead = false;
	private volatile int ldjProgress = -1;
	private volatile String ldjStage = "";

	public MusicSelector(MainController main, boolean songUpdated) {
		super(main);
		this.config = main.getPlayerResource().getPlayerConfig();

		songdb = main.getSongDatabase();

		final PlayDataAccessor pda = main.getPlayDataAccessor();

		scorecache = new ScoreDataCache() {
			@Override
			protected ScoreData readScoreDatasFromSource(SongData song, int lnmode) {
				return pda.readScoreData(song.getSha256(), song.hasUndefinedLongNote(), lnmode, song.isLDJChart());
			}

			@Override
			protected void readScoreDatasFromSource(ScoreDataCollector collector, SongData[] songs, int lnmode) {
				pda.readScoreDatas(collector, songs, lnmode);
			}
		};
		
		bar = new BarRenderer(this, manager);
		banners = new PixmapResourcePool(resource.getConfig().getBannerPixmapGen());
		stagefiles = new PixmapResourcePool(resource.getConfig().getStagefilePixmapGen());
		musicinput = new MusicSelectInputProcessor(this);

		if (!songUpdated && main.getPlayerResource().getConfig().isUpdatesong()) {
			main.updateSong(null);
		}
	}

	public void setRival(PlayerInformation rival) {
        this.rival = rival;
		manager.updateBar();
		logger.info("Rival変更:{}", rival != null ? rival.getName() : "なし");
	}

	public PlayerInformation getRival() {
		return rival;
	}

	public ScoreDataCache getScoreDataCache() {
		return scorecache;
	}

	public void create() {
		main.getSoundManager().shuffle();

		play = null;
		showNoteGraph = false;
		resource.setPlayerData(main.getPlayDataAccessor().readPlayerData());
		if (playedsong != null) {
			scorecache.update(playedsong, config.getLnmode());
			playedsong = null;
		}
		if (playedcourse != null) {
			for (SongData sd : playedcourse.getSong()) {
				scorecache.update(sd, config.getLnmode());
			}
			playedcourse = null;
		}

		preview = new PreviewMusicProcessor(main.getAudioProcessor(), resource.getConfig());
		preview.setDefault(getSound(SELECT));

		final BMSPlayerInputProcessor input = main.getInputProcessor();
		PlayModeConfig pc = (config.getMusicselectinput() == 0 ? config.getMode7()
				: (config.getMusicselectinput() == 1 ? config.getMode9() : config.getMode14()));
		input.setKeyboardConfig(pc.getKeyboardConfig());
		input.setControllerConfig(pc.getController());
		input.setMidiConfig(pc.getMidiConfig());
		setupLDJFolder();
		manager.updateBar();

        loadSkin(SkinType.MUSIC_SELECT);

		// search text field
		Rectangle searchRegion = ((MusicSelectSkin) getSkin()).getSearchTextRegion();
		if (searchRegion != null && (getStage() == null ||
				(search != null && !searchRegion.equals(search.getSearchBounds())))) {
			if(search != null) {
				search.dispose();
			}
			search = new SearchTextField(this, resource.getConfig().getResolution());
			setStage(search);
		}
	}

	public void prepare() {
		preview.start(null);
	}

	/**
	 * LDJ 联动的设置不在玩家个别设置(PlayerConfig)中，而在整体设置(Config)一侧
	 */
	private Config ldjConfig() {
		return main.getPlayerResource().getConfig();
	}

	/**
	 * 初始化 LDJ 联动(iidx2bms)，设置为空或转换环境不可用时
	 * 不做任何处理(不在选曲画面创建 LDJ 文件夹)
	 */
	private void setupLDJFolder() {
		final String projectRoot = ldjConfig().getLDJ_ToolPath();
		if (projectRoot == null || projectRoot.isEmpty()) {
			return;
		}
		final LDJSongProvider provider = new LDJSongProvider(projectRoot);
		if (!provider.isAvailable()) {
			logger.warn("iidx2bms: 変換環境を利用できないためLDJ連携を無効にします: {}", projectRoot);
			return;
		}
		// 未设置 LDJ_Sources 时，按以往方式回退到 OS 临时目录
		final String sourcesRoot = ldjConfig().getLDJ_Sources();
		final LDJTempFileManager tempFiles = (sourcesRoot == null || sourcesRoot.isEmpty())
				? new LDJTempFileManager()
				: new LDJTempFileManager(Paths.get(sourcesRoot));
		if (!tempFiles.prepare()) {
			logger.warn("iidx2bms: 作業ディレクトリを準備できないためLDJ連携を無効にします");
			return;
		}
		ldjProvider = provider;
		ldjTempFiles = tempFiles;
		ldjConverter = new LDJConversionService(tempFiles);
		ldjBar = new LDJFolderBar(this, provider, provider.getSongBars(false), provider.getSongBars(true));
		manager.setAppendDirectoryBar("ldj", ldjBar);
	}

	/**
	 * 组装转换请求，向 outRoot 传入 null 时无法转换，但可用于缓存判定
	 */
	private LDJConversionService.Request newLDJRequest(LDJChartRef ref, Path outRoot) {
		final Config ldj = ldjConfig();
		final LDJConversionService.Request request = new LDJConversionService.Request(ref.getSongId(),
				ref.getSongIdDisplay(), ldj.getLDJ_ToolPath(), ldj.getLDJ_SoundPath(),
				ldj.getLDJ_MoviePath(), outRoot);
		request.includeBga = ldj.isLDJ_IncludeBGA();
		request.includePreview = ldj.isLDJ_IncludePreview();
		return request;
	}

	/**
	 * 为选曲画面显示中的谱面补充统计信息。
	 *
	 * <p>LDJ 谱面此时路径还是 iidx://，没有可读的谱面文件，只能先查转换缓存：
	 * 命中时才解析缓存中的 BMS 并回填统计，未命中时保持空白，不触发转换。</p>
	 */
	private void applyChartInformation(SongData song) {
		if (song == null) {
			return;
		}
		if (song.isLDJChart() && LDJSongProvider.isLDJPath(song.getPath())) {
			final BMSModel model = loadCachedLDJModel(song);
			if (model != null) {
				// 只回填统计，不走 setBMSModel：BMS 头会覆盖 music_data.json 与难度表提供的曲名、流派、等级
				song.setNotes(model.getTotalNotes());
				// BPM推移图的横轴以谱面长度为准，缺了就整张图挤在最初一秒里
				song.setLength(model.getLastTime());
				song.setInformation(new SongInformation(model));
			}
			return;
		}
		song.setBMSModel(resource.loadBMSModel(song, config.getLnmode()));
	}

	/**
	 * 解析已缓存的 LDJ 谱面，没有可用缓存时返回 null
	 */
	private BMSModel loadCachedLDJModel(SongData song) {
		if (ldjConverter == null) {
			return null;
		}
		final LDJChartRef ref = LDJChartRef.parse(song.getPath());
		if (ref == null) {
			return null;
		}
		try {
			final LDJConversionService.Result cached = ldjConverter.readCachedResult(newLDJRequest(ref, null));
			if (cached == null) {
				return null;
			}
			final String token = ref.getDifficulty().getToken();
			for (LDJConversionService.Chart chart : cached.charts) {
				if (token.equalsIgnoreCase(chart.getDifficulty())) {
					return resource.loadBMSModel(chart.resolve(cached.directory), config.getLnmode());
				}
			}
		} catch (LDJConversionService.ConversionException e) {
			logger.warn("iidx2bms: 選曲画面での譜面情報の読み込みに失敗しました: {}", e.getMessage());
		}
		return null;
	}

	/**
	 * 从转换结果中查找所需难度的谱面，把 song 的路径改写为实际 BMS 文件
	 *
	 * @return 仅当找到对应谱面时为 true
	 */
	private boolean applyLDJResult(SongData song, LDJChartRef ref, LDJConversionService.Result result) {
		if (song == null || ref == null || result == null) {
			return false;
		}
		final String token = ref.getDifficulty().getToken();
		for (LDJConversionService.Chart chart : result.charts) {
			if (token.equalsIgnoreCase(chart.getDifficulty())) {
				// 移动到缓存后 manifest 的绝对路径会失效，因此必须用 resolve() 解析
				song.setPath(chart.resolve(result.directory).toString());
				return true;
			}
		}
		logger.warn("iidx2bms: 変換結果に難易度{}の譜面が含まれていません: {}", token, ref.getSongIdDisplay());
		return false;
	}

	/**
	 * 开始 LDJ 谱面的转换
	 *
	 * @return 缓存命中且已处于可读取谱面状态时为 true，转换已开始，
	 *         或者未能开始时为 false(调用方中断处理)
	 */
	private boolean startLDJConversion(SongData song, Bar current) {
		if (ldjConverter == null || ldjProvider == null || ldjTempFiles == null) {
			return false;
		}
		if (ldjThread != null && ldjThread.isAlive()) {
			// 转换同时只允许 1 件，不中断进行中的转换(不调用 cancel())
			ImGuiNotify.info("LDJ譜面を変換中です。完了までお待ちください");
			return false;
		}
		final LDJChartRef ref = LDJChartRef.parse(song.getPath());
		if (ref == null) {
			return false;
		}

		ldjError = null;
		ldjWarning = null;
		ldjResult = null;
		try {
			final LDJConversionService.Result cached = ldjConverter.readCachedResult(newLDJRequest(ref, null));
			if (applyLDJResult(song, ref, cached)) {
				// 缓存命中，song 的路径已改写为实际 BMS，因此进入通常的读取流程
				return true;
			}
		} catch (LDJConversionService.ConversionException e) {
			logger.warn("iidx2bms: キャッシュの読み込みに失敗しました: {}", e.getMessage());
		}

		final Path sessionDir;
		try {
			sessionDir = ldjTempFiles.createSessionDir(ref.getSongIdDisplay());
		} catch (IOException e) {
			ImGuiNotify.error("LDJ譜面の作業ディレクトリを作成できません: " + e.getMessage());
			return false;
		}

		ldjSong = song;
		ldjBarAtStart = current;
		ldjMode = play;
		ldjProceedRead = true;
		ldjProgress = 0;
		ldjStage = "変換を開始しています";
		LDJConversionOverlay.show(ref.getSongIdDisplay());
		ImGuiNotify.info("LDJ譜面を変換しています...");
		// 为避免转换线程读取整体设置，在 UI 线程侧先确定取值再传入
		final int cacheMaxSizeMB = ldjConfig().getLDJ_CacheMaxSizeMB();
		final Thread thread = new Thread(
				() -> runLDJConversion(newLDJRequest(ref, sessionDir), ref, sessionDir, cacheMaxSizeMB),
				"iidx2bms-convert");
		thread.setDaemon(true);
		ldjThread = thread;
		thread.start();
		return false;
	}

	/**
	 * 转换线程的主体，不从 UI 线程触碰，结果通过字段传递
	 */
	private void runLDJConversion(LDJConversionService.Request request, LDJChartRef ref, Path sessionDir,
			int cacheMaxSizeMB) {
		try {
			final LDJConversionService.Result result = ldjConverter.convert(request,
					new LDJConversionService.ProgressListener() {
						@Override
						public void onProgress(int percent, String stage) {
							ldjProgress = percent;
							ldjStage = stage != null ? stage : "";
							LDJConversionOverlay.update(percent, ldjStage);
						}

						@Override
						public void onWarning(String message) {
							logger.warn("iidx2bms: {}", message);
							// 这里跑在转换线程上，不能直接调 ImGuiNotify，
							// 只记录，由 UI 线程在完成通知里提示
							ldjWarning = message;
						}
					});
			// 移动到缓存，即使移动失败也会返回同一目录，因此直接使用
			final Path promoted = ldjTempFiles.promoteToCache(result.directory, ref.getSongIdDisplay());
			ldjTempFiles.enforceCacheLimit(cacheMaxSizeMB);
			ldjResult = new LDJConversionService.Result(promoted, result.charts, result.title, result.artist,
					result.genre, result.songIdDisplay);
			ldjStage = "変換完了";
			ldjProgress = 100;
			LDJConversionOverlay.update(100, "変換完了");
		} catch (LDJConversionService.ConversionException e) {
			ldjTempFiles.discardSessionDir(sessionDir);
			ldjError = e.getMessage();
		} catch (RuntimeException e) {
			ldjTempFiles.discardSessionDir(sessionDir);
			ldjError = e.toString();
			logger.warn("iidx2bms: 変換中に予期しないエラーが発生しました", e);
		}
	}

	/**
	 * 从 render() 接收转换线程的完成通知，若成功则重新开始读取谱面(UI 线程)
	 */
	private void onLDJConversionFinished() {
		if (!ldjProceedRead) {
			return;
		}
		final LDJConversionService.Result result = ldjResult;
		final String error = ldjError;
		final String warning = ldjWarning;
		final SongData song = ldjSong;
		final Bar bar = ldjBarAtStart;
		final BMSPlayerMode mode = ldjMode;

		ldjProceedRead = false;
		ldjResult = null;
		ldjError = null;
		ldjWarning = null;
		ldjSong = null;
		ldjBarAtStart = null;
		ldjMode = null;
		ldjProgress = -1;
		ldjStage = "";
		LDJConversionOverlay.hide();

		final LDJChartRef ref = song != null ? LDJChartRef.parse(song.getPath()) : null;
		if (applyLDJResult(song, ref, result)) {
			// 不绕过通常的读取流程(resource.setBMSFile → DECIDE 迁移)，而是走同一条路径
			// 为避免被紧随其后的 render() 的 play 消费块重复取走，读取后把 play 恢复
			// 路径已由 applyLDJResult 改写为实际 BMS，因此显式指明其源自 LDJ 后传入
			play = mode;
			readChart(song, bar, true);
			play = null;
		} else if (error != null) {
			ImGuiNotify.error(error);
		} else if (result != null) {
			ImGuiNotify.error("LDJ譜面の変換結果から譜面を読み込めませんでした: "
					+ (song != null ? song.getPath() : ""));
		}
		// 警告不是失败(例如 32283 这类歌只能走目录回退)，因此不放在上面的分支里，
		// 成功的转换照样要把警告提示出来
		if (warning != null) {
			ImGuiNotify.warning("LDJ譜面の変換で警告が発生しました: " + warning);
		}
	}

	public void render() {
		final Bar current = manager.getSelected();
        if(timer.getNowTime() > getSkin().getInput()){
        	timer.switchTimer(TIMER_STARTINPUT, true);
        }
		if(timer.getNowTime(TIMER_SONGBAR_CHANGE) < 0) {
			timer.setTimerOn(TIMER_SONGBAR_CHANGE);
		}
		// draw song information
		resource.setSongdata(current instanceof SongBar ? ((SongBar) current).getSongData() : null);
		resource.setCourseData(current instanceof GradeBar ? ((GradeBar) current).getCourseData() : null);

		// preview music
		if (current instanceof SongBar && resource.getConfig().getSongPreview() != SongPreview.NONE) {
			final SongData song = resource.getSongdata();
			if (song != preview.getSongData() && timer.getNowTime() > timer.getTimer(TIMER_SONGBAR_CHANGE) + previewDuration
					&& play == null) {
				this.preview.start(song);
			}
		}

		// read bms information
		if (timer.getNowTime() > timer.getTimer(TIMER_SONGBAR_CHANGE) + notesGraphDuration && !showNoteGraph && play == null) {
			if (current instanceof SongBar && ((SongBar) current).existsSong()) {
				SongData song = resource.getSongdata();
				new Thread(() -> applyChartInformation(song)).start();
			}
			showNoteGraph = true;
		}
		// get ir ranking
		if (currentRankingDuration != -1 && timer.getNowTime() > timer.getTimer(TIMER_SONGBAR_CHANGE) + currentRankingDuration) {
			currentRankingDuration = -1;
			if (current instanceof SongBar && ((SongBar) current).existsSong() && play == null) {
				SongData song = ((SongBar) current).getSongData();
				RankingData irc = main.getRankingDataCache().get(song, config.getLnmode());
				if(irc == null) {
					irc = new RankingData();
					main.getRankingDataCache().put(song, config.getLnmode(), irc);
				}
				irc.load(this, song);
	            currentir = irc;
			}				
			if (current instanceof GradeBar && ((GradeBar) current).existsAllSongs() && play == null) {
				CourseData course = ((GradeBar) current).getCourseData();
				RankingData irc = main.getRankingDataCache().get(course, config.getLnmode());
				if(irc == null) {
					irc = new RankingData();
					main.getRankingDataCache().put(course, config.getLnmode(), irc);
				}
				irc.load(this, course);
	            currentir = irc;
			}				
		}
		final int irstate = currentir != null ? currentir.getState() : -1;
		timer.switchTimer(TIMER_IR_CONNECT_BEGIN, irstate == RankingData.ACCESS);
		timer.switchTimer(TIMER_IR_CONNECT_SUCCESS, irstate == RankingData.FINISH);
		timer.switchTimer(TIMER_IR_CONNECT_FAIL, irstate == RankingData.FAIL);

		if (ldjThread != null && !ldjThread.isAlive()) {
			ldjThread = null;
			onLDJConversionFinished();
		}
		if (play != null) {
			if (current instanceof SongBar) {
				SongData song = ((SongBar) current).getSongData();
				if (((SongBar) current).existsSong()) {
					readChart(song, current);					
				} else if (song.getIpfs() != null && main.getMusicDownloadProcessor() != null
						&& main.getMusicDownloadProcessor().isAlive()) {
					execute(MusicSelectCommand.DOWNLOAD_IPFS);
				} else if (main.getHttpDownloadProcessor() != null) {
					execute(MusicSelectCommand.DOWNLOAD_HTTP);
				} else {
	                executeEvent(EventType.open_download_site);
				}
			} else if (current instanceof ExecutableBar) {
				readChart(((ExecutableBar) current).getSongData(), current);					
			}else if (current instanceof GradeBar) {
				if (play.mode == BMSPlayerMode.Mode.PRACTICE) {
					play = BMSPlayerMode.PLAY;
				}
				readCourse(play);
			} else if (current instanceof RandomCourseBar) {
				if (play.mode == BMSPlayerMode.Mode.PRACTICE) {
					play = BMSPlayerMode.PLAY;
				}
				readRandomCourse(play);
			} else if (current instanceof DirectoryBar) {
				if(play.mode == BMSPlayerMode.Mode.AUTOPLAY) {
					final SongData[] songs = Stream.of(((DirectoryBar) current).getChildren())
						.filter(bar -> (bar instanceof SongBar && ((SongBar) bar).getSongData() != null && ((SongBar) bar).getSongData().getPath() != null))
						.map(bar -> ((SongBar) bar).getSongData()).toArray(SongData[]::new);
					if(songs.length > 0) {
						resource.clear();
						resource.setAutoPlaySongs(songs, false);
						if(resource.nextSong()) {
							main.changeState(MainStateType.DECIDE);
						}
					}
				}
			}
            play = null;
            if (current instanceof FunctionBar) {
                ((FunctionBar)current).accept(this);
            }
        }
	}

	public void input() {
		final BMSPlayerInputProcessor input = main.getInputProcessor();

		if (input.getControlKeyState(ControlKeys.NUM6)) {
			main.changeState(MainStateType.CONFIG);
		} else if (input.isActivated(KeyCommand.OPEN_SKIN_CONFIGURATION)) {
			main.changeState(MainStateType.SKINCONFIG);
		}

		musicinput.input();
	}

	public void shutdown() {
		preview.stop();
		if (search != null) {
			search.unfocus(this);
		}
		banners.disposeOld();
		stagefiles.disposeOld();
	}
	
	public void select(Bar current) {
		if (current instanceof DirectoryBar dirbar) {
			if (manager.updateBar(dirbar)) {
				play(FOLDER_OPEN);
			}
			execute(MusicSelectCommand.RESET_REPLAY);
		} else {
			play = BMSPlayerMode.PLAY;
		}
	}

	public int getSelectedReplay() {
		return  selectedreplay;
	}

	public void setSelectedReplay(int index) {
		selectedreplay = index;
	}

	public void execute(MusicSelectCommand command) {
		command.function.accept(this);
	}

	public void readChart(SongData song, Bar current) {
		// 已转换的 LDJ 谱面路径已改写为实际 BMS，因此也用虚拟分数键的有无来判定
		readChart(song, current, LDJSongProvider.isLDJPath(song.getPath()) || song.isLDJChart());
	}

	/**
	 * @param fromLDJ 是否作为源自 LDJ 的谱面读取，转换完成后的再读取中，路径
	 *                已改写为实际 BMS，无法从路径判定，因此由调用方显式指定
	 */
	public void readChart(SongData song, Bar current, boolean fromLDJ) {
		if (fromLDJ && !LDJSongProvider.isLDJPath(song.getPath())) {
			// 路径已被改写成实际 BMS；若缓存被删导致该文件不存在，恢复 iidx:// 源路径以便重转
			final String sourcePath = song.getLDJPath();
			if (sourcePath != null && !sourcePath.isEmpty()
					&& !Files.exists(Paths.get(song.getPath()))) {
				song.setPath(sourcePath);
			}
		}
		if (LDJSongProvider.isLDJPath(song.getPath())) {
			// 为使路径因转换改写为实际文件后也不丢失虚拟分数键，在此固定
			// 缓存命中时会在 startLDJConversion 内同步改写路径，因此必须在此之前调用
			song.setLDJScoreKey(song.getSha256());
			if (!startLDJConversion(song, current)) {
				// 已开始转换，或未能开始，完成时由 render() 重新读取
				return;
			}
			// 缓存命中，song 的路径已改写为实际 BMS 文件，因此进入通常流程
		}
		resource.clear();
		resource.setFromLDJ(fromLDJ);
		if (resource.setBMSFile(song, play)) {
			// TODO 表名、フォルダ名をPlayerResource上でも重複実施している
			final Queue<DirectoryBar> dir = manager.getDirectory();
			if(dir.size > 0 && !(dir.last() instanceof SameFolderBar)) {
				Array<String> urls = new Array<String>(resource.getConfig().getTableURL());

				boolean isdtable = false;
				for (DirectoryBar bar : dir) {
					if (bar instanceof TableBar) {
						String currenturl = ((TableBar) bar).getUrl();
						if (currenturl != null && (urls.contains(currenturl, false) || ((TableBar) bar).isBackbeat())) {
							isdtable = true;
							resource.setTablename(bar.getTitle());
						}
					}
					if (bar instanceof HashBar && isdtable) {
						resource.setTablelevel(bar.getTitle());
						break;
					}
				}
			}
			
			// LDJ 难度表的谱面，把表名，文件夹名，谱面标签用" | "连接后送入 tablefullname(1003)
			// 上面的 dir 循环会因 SameFolderBar 直接略过，因此在该循环之外判定
			String ldjTable = null;
			String ldjGroup = null;
			for (DirectoryBar bar : dir) {
				if (bar instanceof LDJTableFileBar) {
					ldjTable = bar.getTitle();
				} else if (bar instanceof LDJTableGroupBar) {
					ldjGroup = bar.getTitle();
				}
			}
			if (ldjTable != null) {
				String ldjtablefull = "";
				// 标签存放在 SongBar 的 SongData 中，因为 resource.setBMSFile 会重新读取实际 BMS，所以一并在此获取
				for (String part : new String[] { ldjTable, ldjGroup, song.getSubtitle() }) {
					if (part == null || part.isEmpty()) {
						continue;
					}
					if (ldjtablefull.length() > 0) {
						ldjtablefull += " | ";
					}
					ldjtablefull += part;
				}
				if (ldjtablefull.length() > 0) {
					resource.setTablelevel("");
					resource.setTablename(ldjtablefull);
				}
			}
			
			if(main.getIRStatus().length > 0 && currentir == null) {
				currentir = new RankingData();
				main.getRankingDataCache().put(song, config.getLnmode(), currentir);
			}
			resource.setRankingData(currentir);
			ScoreData rival = current.getRivalScore();
			resource.setRivalScoreData(rival);
			ReplayData chartOption = null;
			ReplayData replay;
			switch(ChartReplicationMode.get(config.getChartReplicationMode())) {
			case NONE:
				// TODO 通常オプションもここに入れて渡す？
				break;
			case RIVALCHART:
				if(rival != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = rival.getOption() % 10;
					chartOption.randomoption2 = (rival.getOption() / 10) % 10;
					chartOption.doubleoption = rival.getOption() / 100;
					chartOption.randomoptionseed = rival.getSeed() % (65536 * 256);
					chartOption.randomoption2seed = rival.getSeed() / (65536 * 256);
//					chartOption.rand = rival.getRandom();
				}
				break;
			case RIVALOPTION:
				if(rival != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = rival.getOption() % 10;
					chartOption.randomoption2 = (rival.getOption() / 10) % 10;
					chartOption.doubleoption = rival.getOption() / 100;
				}
				break;							
			case REPLAYCHART:
				replay = main.getPlayDataAccessor().readReplayData(resource.getBMSModel(), config.getLnmode(), play.id);
				if (replay != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = replay.randomoption;
					chartOption.randomoptionseed = replay.randomoptionseed;
					chartOption.randomoption2 = replay.randomoption2;
					chartOption.randomoption2seed = replay.randomoption2seed;
					chartOption.doubleoption = replay.doubleoption;
					chartOption.rand = replay.rand;
				}
				break;
			case REPLAYOPTION:
				replay = main.getPlayDataAccessor().readReplayData(resource.getBMSModel(), config.getLnmode(), play.id);
				if (replay != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = replay.randomoption;
					chartOption.randomoption2 = replay.randomoption2;
					chartOption.doubleoption = replay.doubleoption;
				}
				break;
			}
			resource.setChartOption(chartOption);
			
			playedsong = song;
			main.changeState(MainStateType.DECIDE);
		} else {
			ImGuiNotify.error("Failed to loading BMS : Song not found, or Song has error", 1200);
		}
	}
	
	private void readCourse(BMSPlayerMode mode) {
		final GradeBar gradeBar = (GradeBar) manager.getSelected();
		if (!gradeBar.existsAllSongs()) {
			logger.info("段位の楽曲が揃っていません");
            if (main.getHttpDownloadProcessor() != null) {
                execute(MusicSelectCommand.DOWNLOAD_COURSE_HTTP);
            }
			return;
		}

		if (!_readCourse(mode, gradeBar)) {
			ImGuiNotify.error("Failed to loading Course : Some of songs not found", 1200);
			logger.info("段位の楽曲が揃っていません");
		}
	}

	private void readRandomCourse(BMSPlayerMode mode) {
		final RandomCourseBar randomCourseBar = (RandomCourseBar) manager.getSelected();
		if (!randomCourseBar.existsAllSongs()) {
			logger.info("ランダムコースの楽曲が揃っていません");
			return;
		}

		randomCourseBar.getCourseData().lotterySongDatas(main);
		final GradeBar gradeBar = new GradeBar(randomCourseBar.getCourseData().createCourseData());
		if (!gradeBar.existsAllSongs()) {
			ImGuiNotify.error("Failed to loading Random Course : Some of songs not found", 1200);
			logger.info("ランダムコースの楽曲が揃っていません");
			return;
		}

		if (_readCourse(mode, gradeBar)) {
			manager.addRandomCourse(gradeBar, manager.getDirectoryString());
			manager.updateBar();
			manager.setSelected(gradeBar);
		} else {
			ImGuiNotify.error("Failed to loading Random Course : Some of songs not found", 1200);
			logger.info("ランダムコースの楽曲が揃っていません");
		}
	}

	private boolean _readCourse(BMSPlayerMode mode, GradeBar gradeBar) {
		resource.clear();
		final SongData[] songs = gradeBar.getSongDatas();
		if (resource.setCourseBMSFiles(songs)) {
			if (mode.mode == BMSPlayerMode.Mode.PLAY || mode.mode == BMSPlayerMode.Mode.AUTOPLAY) {
				for (CourseData.CourseDataConstraint constraint : gradeBar.getCourseData().getConstraint()) {
					switch (constraint) {
						case CLASS:
							config.setRandom(0);
							config.setRandom2(0);
							config.setDoubleoption(0);
							break;
						case MIRROR:
							if (config.getRandom() == 1) {
								config.setRandom2(1);
								config.setDoubleoption(1);
							} else {
								config.setRandom(0);
								config.setRandom2(0);
								config.setDoubleoption(0);
							}
							break;
						case RANDOM:
							if (config.getRandom() > 5) {
								config.setRandom(0);
							}
							if (config.getRandom2() > 5) {
								config.setRandom2(0);
							}
							break;
						case LN:
							config.setLnmode(0);
							break;
						case CN:
							config.setLnmode(1);
							break;
						case HCN:
							config.setLnmode(2);
							break;
						default:
							break;
					}
				}
			}
			var courseModels = resource.getCourseBMSModels();
			for (int i = 0; i < songs.length; i++) {
				songs[i].setBMSModel(courseModels[i]);
			}
			resource.setCourseData(gradeBar.getCourseData());
			resource.setBMSFile(songs[0], mode);
			playedcourse = gradeBar.getCourseData();

			if(main.getIRStatus().length > 0 && currentir == null) {
				currentir = new RankingData();
				main.getRankingDataCache().put(gradeBar.getCourseData(), config.getLnmode(), currentir);
			}
			
			RankingData songrank = main.getRankingDataCache().get(songs[0], config.getLnmode());
			if(main.getIRStatus().length > 0 && songrank == null) {
				songrank = new RankingData();
				main.getRankingDataCache().put(songs[0], config.getLnmode(), songrank);
			}
			resource.setRankingData(songrank);
			resource.setRivalScoreData(null);
			resource.setChartOption(null);

			main.changeState(MainStateType.DECIDE);
			return true;
		}
		return false;
	}

	public int getSort() {
		return config.getSort();
	}

	public void setSort(int sort) {
		config.setSort(sort);
		config.setSortid(BarSorter.defaultSorter[sort].name());
	}

	public void dispose() {
		super.dispose();
		bar.dispose();
		banners.dispose();
		stagefiles.dispose();
		if (search != null) {
			search.dispose();
			search = null;
		}
	}

	public int getPanelState() {
		return panelstate;
	}

	public void setPanelState(int panelstate) {
		if (this.panelstate != panelstate) {
			if (this.panelstate != 0) {
				timer.setTimerOn(TIMER_PANEL1_OFF + this.panelstate - 1);
				timer.setTimerOff(TIMER_PANEL1_ON + this.panelstate - 1);
			}
			if (panelstate != 0) {
				timer.setTimerOn(TIMER_PANEL1_ON + panelstate - 1);
				timer.setTimerOff(TIMER_PANEL1_OFF + panelstate - 1);
			}
		}
		this.panelstate = panelstate;
	}

	public SongDatabaseAccessor getSongDatabase() {
		return songdb;
	}

	public boolean existsConstraint(CourseData.CourseDataConstraint constraint) {
		CourseData.CourseDataConstraint[] cons;
		if ((manager.getSelected() instanceof GradeBar)) {
			cons = ((GradeBar) manager.getSelected()).getCourseData().getConstraint();
		} else if (manager.getSelected() instanceof RandomCourseBar) {
			cons = ((RandomCourseBar) manager.getSelected()).getCourseData().getConstraint();
		} else {
			return false;
		}

		for (CourseData.CourseDataConstraint con : cons) {
			if(con == constraint) {
				return true;
			}
		}
		return false;
	}

	public Bar getSelectedBar() {
		return manager.getSelected();
	}

	public BarRenderer getBarRender() {
		return bar;
	}

	public BarManager getBarManager() {
		return manager;
	}

	public PixmapResourcePool getBannerResource() {
		return banners;
	}
	public PixmapResourcePool getStagefileResource() {
		return stagefiles;
	}

	public void selectedBarMoved() {
		execute(MusicSelectCommand.RESET_REPLAY);
		loadSelectedSongImages();

		timer.setTimerOn(TIMER_SONGBAR_CHANGE);
		if(preview.getSongData() != null && (!(manager.getSelected() instanceof SongBar) ||
				((SongBar) manager.getSelected()).getSongData().getFolder().equals(preview.getSongData().getFolder()) == false))
		preview.start(null);
		showNoteGraph = false;

		final Bar current = manager.getSelected();
		if(main.getIRStatus().length > 0) {
			if(current instanceof SongBar && ((SongBar) current).existsSong()) {
				currentir = main.getRankingDataCache().get(((SongBar) current).getSongData(), config.getLnmode());
				currentRankingDuration = (currentir != null ? Math.max(rankingReloadDuration - (System.currentTimeMillis() - currentir.getLastUpdateTime()) ,0) : 0) + rankingDuration;
			} else if(current instanceof GradeBar && ((GradeBar) current).existsAllSongs()) {
				currentir = main.getRankingDataCache().get(((GradeBar) current).getCourseData(), config.getLnmode());
				currentRankingDuration = (currentir != null ? Math.max(rankingReloadDuration - (System.currentTimeMillis() - currentir.getLastUpdateTime()) ,0) : 0) + rankingDuration;
			} else {
				currentir = null;
				currentRankingDuration = -1;			
			}
		} else {
			currentir = null;
			currentRankingDuration = -1;			
		}
	}

	public void loadSelectedSongImages() {
		// banner
		// stagefile
		final Bar current = manager.getSelected();
		resource.getBMSResource().setBanner(
				current instanceof SongBar ? ((SongBar) current).getBanner() : null);
		resource.getBMSResource().setStagefile(
				current instanceof SongBar ? ((SongBar) current).getStagefile() : null);
	}

	public void selectSong(BMSPlayerMode mode) {
		play = mode;
	}

	public PlayConfig getSelectedBarPlayConfig() {
		Bar current = manager.getSelected();
		PlayConfig pc = null;
		if (current instanceof SongBar && ((SongBar)current).existsSong()) {
			SongBar song = (SongBar) current;
			pc = main.getPlayerConfig().getPlayConfig(song.getSongData().getMode()).getPlayconfig();
		} else if(current instanceof GradeBar && ((GradeBar)current).existsAllSongs()) {
			GradeBar grade = (GradeBar)current;
			for(SongData song : grade.getSongDatas()) {
				PlayConfig pc2 = main.getPlayerConfig().getPlayConfig(song.getMode()).getPlayconfig();
				if(pc == null) {
					pc = pc2;
				}
				if(pc != pc2) {
					pc = null;
					break;
				}
			}
		} else {
			pc = main.getPlayerConfig().getPlayConfig(config.getMode()).getPlayconfig();
		}
		return pc;
	}
	
	public RankingData getCurrentRankingData() {
		return currentir;
	}
	
	public long getCurrentRankingDuration() {
		return currentRankingDuration;
	}
	
	public int getRankingOffset() {
		return rankingOffset;
	}
	
	public float getRankingPosition() {
		final int rankingMax = currentir != null ? Math.max(1, currentir.getTotalPlayer()) : 1;
		return (float)rankingOffset / rankingMax;		
	}
	
	public void setRankingPosition(float value) {
		if (value >= 0 && value < 1) {
			final int rankingMax = currentir != null ? Math.max(1, currentir.getTotalPlayer()) : 1;
			rankingOffset = (int) (rankingMax * value);
		}
	}

	public enum ChartReplicationMode {
		NONE, RIVALCHART, RIVALOPTION, REPLAYCHART, REPLAYOPTION;
		
		public static final ChartReplicationMode[] allMode = {NONE, RIVALCHART, RIVALOPTION};
		
		public static ChartReplicationMode get(String name) {
			for(ChartReplicationMode mode : allMode) {
				if(mode.name().equals(name)) {
					return mode;
				}
			}
			return NONE;
		}
		
	}
}
