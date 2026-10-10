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
import bms.player.beatoraja.iidx.IIDXChartRef;
import bms.player.beatoraja.iidx.IIDXConversionOverlay;
import bms.player.beatoraja.iidx.IIDXConversionService;
import bms.player.beatoraja.iidx.IIDXSongProvider;
import bms.player.beatoraja.iidx.IIDXTempFileManager;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyCommand;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.ir.*;
import bms.player.beatoraja.select.bar.*;
import bms.player.beatoraja.skin.SkinType;
import bms.player.beatoraja.skin.property.EventFactory.EventType;
import bms.player.beatoraja.song.SongData;
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

	// IIDX連携(iidx2bms)。config で有効かつ変換環境が使える場合のみ非null
	private IIDXSongProvider iidxProvider;
	private IIDXTempFileManager iidxTempFiles;
	private IIDXConversionService iidxConverter;
	private Bar iidxBar;
	// 変換スレッド関連。完了検出は render() での isAlive() ポーリング
	private Thread iidxThread;
	private SongData iidxSong;
	private Bar iidxBarAtStart;
	private BMSPlayerMode iidxMode;
	// isAlive()による完了検出はhappens-beforeを保証しないため、ワーカーが書くフィールドはvolatileにする
	private volatile IIDXConversionService.Result iidxResult;
	private volatile String iidxError;
	private boolean iidxProceedRead = false;
	private volatile int iidxProgress = -1;
	private volatile String iidxStage = "";

	public MusicSelector(MainController main, boolean songUpdated) {
		super(main);
		this.config = main.getPlayerResource().getPlayerConfig();

		songdb = main.getSongDatabase();

		final PlayDataAccessor pda = main.getPlayDataAccessor();

		scorecache = new ScoreDataCache() {
			@Override
			protected ScoreData readScoreDatasFromSource(SongData song, int lnmode) {
				return pda.readScoreData(song.getSha256(), song.hasUndefinedLongNote(), lnmode);
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
		setupIIDXFolder();
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
	 * IIDX連携の設定はプレイヤー個別設定(PlayerConfig)ではなく全体設定(Config)側にある
	 */
	private Config iidxConfig() {
		return main.getPlayerResource().getConfig();
	}

	/**
	 * IIDX連携(iidx2bms)を初期化する。設定が空、または変換環境が使えない場合は
	 * 何もしない(選曲画面にIIDXフォルダを作らない)
	 */
	private void setupIIDXFolder() {
		final String projectRoot = iidxConfig().getIidx2bmsPath();
		if (projectRoot == null || projectRoot.isEmpty()) {
			return;
		}
		final IIDXSongProvider provider = new IIDXSongProvider(projectRoot);
		if (!provider.isAvailable()) {
			logger.warn("iidx2bms: 変換環境を利用できないためIIDX連携を無効にします: {}", projectRoot);
			return;
		}
		final IIDXTempFileManager tempFiles = new IIDXTempFileManager();
		if (!tempFiles.prepare()) {
			logger.warn("iidx2bms: 作業ディレクトリを準備できないためIIDX連携を無効にします");
			return;
		}
		iidxProvider = provider;
		iidxTempFiles = tempFiles;
		iidxConverter = new IIDXConversionService(tempFiles);
		iidxBar = new IIDXFolderBar(this, "IIDX", provider.getSongBars(false), provider.getSongBars(true));
		manager.setAppendDirectoryBar("iidx", iidxBar);
	}

	/**
	 * 変換要求を組み立てる。outRootにnullを渡すと変換はできないが、キャッシュ判定には使える
	 */
	private IIDXConversionService.Request newIIDXRequest(IIDXChartRef ref, Path outRoot) {
		final Config iidx = iidxConfig();
		final IIDXConversionService.Request request = new IIDXConversionService.Request(ref.getSongId(),
				ref.getSongIdDisplay(), iidx.getIidx2bmsPath(), iidx.getIidxSoundPath(),
				iidx.getIidxMoviePath(), outRoot);
		request.includeBga = iidx.isIidxIncludeBGA();
		request.includePreview = iidx.isIidxIncludePreview();
		return request;
	}

	/**
	 * 変換結果から要求された難易度の譜面を探し、songのパスを実BMSファイルへ書き換える
	 *
	 * @return 該当する譜面が見つかった場合のみtrue
	 */
	private boolean applyIIDXResult(SongData song, IIDXChartRef ref, IIDXConversionService.Result result) {
		if (song == null || ref == null || result == null) {
			return false;
		}
		final String token = ref.getDifficulty().getToken();
		for (IIDXConversionService.Chart chart : result.charts) {
			if (token.equalsIgnoreCase(chart.getDifficulty())) {
				// キャッシュへ移動するとmanifestの絶対パスは無効になるため、必ずresolve()で解決する
				song.setPath(chart.resolve(result.directory).toString());
				return true;
			}
		}
		logger.warn("iidx2bms: 変換結果に難易度{}の譜面が含まれていません: {}", token, ref.getSongIdDisplay());
		return false;
	}

	/**
	 * IIDX譜面の変換を開始する。
	 *
	 * @return キャッシュヒットして譜面を読み込める状態になった場合はtrue。変換を開始した、
	 *         あるいは開始できなかった場合はfalse(呼び出し元は処理を打ち切る)
	 */
	private boolean startIIDXConversion(SongData song, Bar current) {
		if (iidxConverter == null || iidxProvider == null || iidxTempFiles == null) {
			return false;
		}
		if (iidxThread != null && iidxThread.isAlive()) {
			// 変換は同時に1件のみ。進行中の変換は中断しない(cancel()は呼ばない)
			ImGuiNotify.info("IIDX譜面を変換中です。完了までお待ちください");
			return false;
		}
		final IIDXChartRef ref = IIDXChartRef.parse(song.getPath());
		if (ref == null) {
			return false;
		}

		iidxError = null;
		iidxResult = null;
		try {
			final IIDXConversionService.Result cached = iidxConverter.readCachedResult(newIIDXRequest(ref, null));
			if (applyIIDXResult(song, ref, cached)) {
				// キャッシュヒット。songのパスは実BMSへ書き換え済みなので通常の読み込みフローへ進む
				return true;
			}
		} catch (IIDXConversionService.ConversionException e) {
			logger.warn("iidx2bms: キャッシュの読み込みに失敗しました: {}", e.getMessage());
		}

		final Path sessionDir;
		try {
			sessionDir = iidxTempFiles.createSessionDir(ref.getSongIdDisplay());
		} catch (IOException e) {
			ImGuiNotify.error("IIDX譜面の作業ディレクトリを作成できません: " + e.getMessage());
			return false;
		}

		iidxSong = song;
		iidxBarAtStart = current;
		iidxMode = play;
		iidxProceedRead = true;
		iidxProgress = 0;
		iidxStage = "変換を開始しています";
		IIDXConversionOverlay.show(ref.getSongIdDisplay());
		ImGuiNotify.info("IIDX譜面を変換しています...");
		// 変換スレッドから全体設定を読まないよう、UIスレッド側で値を確定させて渡す
		final int cacheMaxSizeMB = iidxConfig().getIidxCacheMaxSizeMB();
		final Thread thread = new Thread(
				() -> runIIDXConversion(newIIDXRequest(ref, sessionDir), ref, sessionDir, cacheMaxSizeMB),
				"iidx2bms-convert");
		thread.setDaemon(true);
		iidxThread = thread;
		thread.start();
		return false;
	}

	/**
	 * 変換スレッドの本体。UIスレッドからは触らず、結果はフィールド経由で受け渡す
	 */
	private void runIIDXConversion(IIDXConversionService.Request request, IIDXChartRef ref, Path sessionDir,
			int cacheMaxSizeMB) {
		try {
			final IIDXConversionService.Result result = iidxConverter.convert(request,
					new IIDXConversionService.ProgressListener() {
						@Override
						public void onProgress(int percent, String stage) {
							iidxProgress = percent;
							iidxStage = stage != null ? stage : "";
							IIDXConversionOverlay.update(percent, iidxStage);
						}

						@Override
						public void onWarning(String message) {
							logger.warn("iidx2bms: {}", message);
						}
					});
			// キャッシュへ移動する。移動に失敗しても同じディレクトリが返るため、そのまま使用する
			final Path promoted = iidxTempFiles.promoteToCache(result.directory, ref.getSongIdDisplay());
			iidxTempFiles.enforceCacheLimit(cacheMaxSizeMB);
			iidxResult = new IIDXConversionService.Result(promoted, result.charts, result.title, result.artist,
					result.genre, result.songIdDisplay);
			iidxStage = "変換完了";
			iidxProgress = 100;
			IIDXConversionOverlay.update(100, "変換完了");
		} catch (IIDXConversionService.ConversionException e) {
			iidxTempFiles.discardSessionDir(sessionDir);
			iidxError = e.getMessage();
		} catch (RuntimeException e) {
			iidxTempFiles.discardSessionDir(sessionDir);
			iidxError = e.toString();
			logger.warn("iidx2bms: 変換中に予期しないエラーが発生しました", e);
		}
	}

	/**
	 * 変換スレッドの完了をrender()から受け取り、成功していれば譜面の読み込みを再開する(UIスレッド)
	 */
	private void onIIDXConversionFinished() {
		if (!iidxProceedRead) {
			return;
		}
		final IIDXConversionService.Result result = iidxResult;
		final String error = iidxError;
		final SongData song = iidxSong;
		final Bar bar = iidxBarAtStart;
		final BMSPlayerMode mode = iidxMode;

		iidxProceedRead = false;
		iidxResult = null;
		iidxError = null;
		iidxSong = null;
		iidxBarAtStart = null;
		iidxMode = null;
		iidxProgress = -1;
		iidxStage = "";
		IIDXConversionOverlay.hide();

		final IIDXChartRef ref = song != null ? IIDXChartRef.parse(song.getPath()) : null;
		if (applyIIDXResult(song, ref, result)) {
			// 通常の読み込みフロー(resource.setBMSFile → DECIDE遷移)を迂回せず、同じ経路を通す。
			// 直後のrender()のplay消費ブロックに二重に拾われないよう、読み込み後はplayを戻す
			play = mode;
			readChart(song, bar);
			play = null;
		} else if (error != null) {
			ImGuiNotify.error(error);
		} else if (result != null) {
			ImGuiNotify.error("IIDX譜面の変換結果から譜面を読み込めませんでした: "
					+ (song != null ? song.getPath() : ""));
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
				new Thread(() ->  {
					song.setBMSModel(resource.loadBMSModel(Paths.get(((SongBar) current).getSongData().getPath()),
							config.getLnmode()));
				}).start();;
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

		if (iidxThread != null && !iidxThread.isAlive()) {
			iidxThread = null;
			onIIDXConversionFinished();
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
					final Path[] paths = Stream.of(((DirectoryBar) current).getChildren())
						.filter(bar -> (bar instanceof SongBar && ((SongBar) bar).getSongData() != null && ((SongBar) bar).getSongData().getPath() != null))
						.map(bar -> Paths.get(((SongBar) bar).getSongData().getPath())).toArray(Path[]::new);
					if(paths.length > 0) {
						resource.clear();
						resource.setAutoPlaySongs(paths, false);
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
		if (IIDXSongProvider.isIIDXPath(song.getPath())) {
			if (!startIIDXConversion(song, current)) {
				// 変換を開始した、または開始できなかった。完了時にrender()から読み直す
				return;
			}
			// キャッシュヒット。songのパスは実BMSファイルへ書き換え済みなので通常フローへ進む
		}
		resource.clear();
		if (resource.setBMSFile(Paths.get(song.getPath()), play)) {
			// TODO 表名、フォルダ名をPlayerResource上でも重複実施している
			final Queue<DirectoryBar> dir = manager.getDirectory();
			if(dir.size > 0 && !(dir.last() instanceof SameFolderBar)) {
				Array<String> urls = new Array<String>(resource.getConfig().getTableURL());

				boolean isdtable = false;
				for (DirectoryBar bar : dir) {
					if (bar instanceof TableBar) {
						String currenturl = ((TableBar) bar).getUrl();
						if (currenturl != null && urls.contains(currenturl, false)) {
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
		final Path[] files = Stream.of(songs).map(song -> Paths.get(song.getPath())).toArray(Path[]::new);
		if (resource.setCourseBMSFiles(files)) {
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
			gradeBar.getCourseData().setSong(resource.getCourseBMSModels());
			resource.setCourseData(gradeBar.getCourseData());
			resource.setBMSFile(files[0], mode);
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
