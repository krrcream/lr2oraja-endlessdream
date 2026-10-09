package bms.player.beatoraja.iidx;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * iidx2bmsへのブリッジスクリプト({@code cli_convert.py})を起動してIIDX譜面をBMSへ変換する。
 *
 * <p>スクリプトは1行1JSONで進捗と結果をstdoutへ出力する。コンバータ本体のログはstderrへ
 * 流れるため、JSONとして解釈できない行は単に読み飛ばす。</p>
 *
 * @author endlessdream
 */
public class IIDXConversionService {

	private static final Logger logger = LoggerFactory.getLogger(IIDXConversionService.class);

	private static final Logger converterLogger = LoggerFactory.getLogger("iidx2bms");

	/** 変換全体の既定タイムアウト(秒) */
	public static final int DEFAULT_TIMEOUT_SECONDS = 180;

	/** 変換結果ディレクトリへ書き出されるマニフェストのファイル名 */
	public static final String MANIFEST_FILE = "manifest.json";

	private final IIDXTempFileManager tempFiles;

	/** 使用するPythonインタプリタの起動コマンド(例: ["py", "-3"])。未検出ならnull */
	private List<String> interpreter;

	private boolean interpreterResolved;

	/** 実行中のプロセス。キャンセル用 */
	private volatile Process currentProcess;
	private volatile boolean cancelled;

	public IIDXConversionService(IIDXTempFileManager tempFiles) {
		this.tempFiles = tempFiles;
	}

	/**
	 * 変換結果1譜面分。{@code file}はマニフェストが報告した絶対パスであり、
	 * キャッシュへ移動すると無効になるため{@link #resolve(Path)}で解決すること。
	 */
	public static class Chart {
		private final String difficulty;
		private final String name;
		private final String file;
		private final int level;

		public Chart(String difficulty, String name, String file, int level) {
			this.difficulty = difficulty;
			this.name = name;
			this.file = file;
			this.level = level;
		}

		public String getDifficulty() {
			return difficulty;
		}

		public String getName() {
			return name;
		}

		public int getLevel() {
			return level;
		}

		/**
		 * 変換ディレクトリを基準にした実際の譜面ファイルのパス
		 */
		public Path resolve(Path baseDir) {
			if (file != null && !file.isEmpty()) {
				try {
					Path relative = baseDir.relativize(Paths.get(file).toAbsolutePath().normalize()).normalize();
					if (!relative.isAbsolute() && relative.getNameCount() > 0
							&& !"..".equals(relative.getName(0).toString())) {
						return baseDir.resolve(relative);
					}
				} catch (IllegalArgumentException e) {
					// 別ドライブ上にある場合はファイル名から解決する
				}
			}
			return baseDir.resolve(name);
		}
	}

	/**
	 * 変換結果。{@code resultDir}は移動後には使えないため、確定した配置先も保持する
	 */
	public static class Result {
		public final Path directory;
		public final List<Chart> charts;
		public final String title;
		public final String artist;
		public final String genre;
		public final String songIdDisplay;

		public Result(Path directory, List<Chart> charts, String title, String artist, String genre, String songIdDisplay) {
			this.directory = directory;
			this.charts = charts;
			this.title = title;
			this.artist = artist;
			this.genre = genre;
			this.songIdDisplay = songIdDisplay;
		}
	}

	/**
	 * 変換要求
	 */
	public static class Request {
		public final String songId;
		public final String songIdDisplay;
		public final String projectRoot;
		public final String soundRoot;
		public final String movieRoot;
		public final Path outRoot;
		public boolean includeBga = true;
		public boolean includePreview = true;
		public boolean overwrite;
		public int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

		public Request(String songId, String songIdDisplay, String projectRoot, String soundRoot, String movieRoot, Path outRoot) {
			this.songId = songId;
			this.songIdDisplay = songIdDisplay;
			this.projectRoot = projectRoot;
			this.soundRoot = soundRoot;
			this.movieRoot = movieRoot;
			this.outRoot = outRoot;
		}
	}

	/**
	 * 進捗通知先。呼び出し元スレッドから呼ばれる
	 */
	public interface ProgressListener {
		void onProgress(int percent, String stage);

		void onWarning(String message);
	}

	/**
	 * 変換に失敗したことを示す。メッセージはそのままユーザーへ通知できる
	 */
	public static class ConversionException extends Exception {
		private static final long serialVersionUID = 1L;

		public ConversionException(String message) {
			super(message);
		}

		public ConversionException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Pythonインタプリタが見つからない場合のエラー
	 */
	public static class InterpreterNotFoundException extends ConversionException {
		private static final long serialVersionUID = 1L;

		public InterpreterNotFoundException(String message) {
			super(message);
		}
	}

	/**
	 * 進行中の変換を中断する。プロセスを強制終了する
	 */
	public void cancel() {
		cancelled = true;
		Process process = currentProcess;
		if (process != null) {
			process.destroyForcibly();
		}
	}

	public boolean isCancelled() {
		return cancelled;
	}

	/**
	 * 変換を実行する。この呼び出しはブロックするため、呼び出し側で別スレッドに載せること
	 */
	public Result convert(Request request, ProgressListener listener) throws ConversionException {
		if (tempFiles.getBridgeScript() == null && !tempFiles.prepare()) {
			throw new ConversionException("IIDX temporary directory is unavailable");
		}
		List<String> interpreterCommand = resolveInterpreter(request);
		if (interpreterCommand == null) {
			throw new InterpreterNotFoundException(
					"Python 3 with ifstools was not found. Install it with: pip install ifstools");
		}

		List<String> command = new ArrayList<String>(interpreterCommand);
		command.add(tempFiles.getBridgeScript().toString());
		command.add("--project-root");
		command.add(request.projectRoot);
		command.add("--sound-root");
		command.add(request.soundRoot);
		command.add("--movie-root");
		command.add(request.movieRoot);
		command.add("--song-id");
		command.add(request.songId);
		command.add("--out-root");
		command.add(request.outRoot.toString());
		if (!request.includeBga) {
			command.add("--no-bga");
		}
		if (!request.includePreview) {
			command.add("--no-preview");
		}
		if (request.overwrite) {
			command.add("--overwrite");
		}

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		builder.directory(new File(tempFiles.getBaseDir().toString()));

		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			throw new ConversionException("Failed to start python: " + e.getMessage(), e);
		}
		currentProcess = process;

		Thread watchdog = startWatchdog(process, request.timeoutSeconds);

		JsonValue result = null;
		String errorMessage = null;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				JsonValue message = parseLine(line);
				if (message == null) {
					continue;
				}
				String type = message.getString("type", "");
				if ("progress".equals(type)) {
					if (listener != null) {
						listener.onProgress(message.getInt("percent", 0), message.getString("stage", ""));
					}
				} else if ("warning".equals(type)) {
					String text = message.getString("message", "");
					logger.warn("iidx2bms: {}", text);
					if (listener != null) {
						listener.onWarning(text);
					}
				} else if ("result".equals(type)) {
					result = message;
				} else if ("error".equals(type)) {
					errorMessage = message.getString("message", "unknown error");
					logger.error("iidx2bms: {}\n{}", errorMessage, message.getString("traceback", ""));
				}
			}
		} catch (IOException e) {
			if (!cancelled) {
				throw new ConversionException("Failed to read the converter output: " + e.getMessage(), e);
			}
		} finally {
			watchdog.interrupt();
			currentProcess = null;
		}

		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (cancelled) {
			throw new ConversionException("Conversion cancelled");
		}
		if (errorMessage != null) {
			throw new ConversionException(errorMessage);
		}
		if (result == null) {
			throw new ConversionException("The converter exited with code " + process.exitValue() + " without a result");
		}
		return parseResult(result, request);
	}

	/**
	 * キャッシュ済みの変換結果を読み込む。マニフェストが無い、壊れている、
	 * あるいは変換条件が現在のリクエストと一致しない場合はnullを返す
	 */
	public Result readCachedResult(Request request) throws ConversionException {
		Path cacheDir = tempFiles.getCachedSongDir(request.songIdDisplay);
		if (cacheDir == null) {
			return null;
		}
		Path manifest = cacheDir.resolve(MANIFEST_FILE);
		if (!Files.isRegularFile(manifest)) {
			return null;
		}
		JsonValue payload;
		try {
			payload = new JsonReader().parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8));
		} catch (IOException e) {
			logger.warn("iidx2bms: failed to read the cached manifest {}", manifest, e);
			return null;
		} catch (RuntimeException e) {
			logger.warn("iidx2bms: the cached manifest {} is not valid JSON", manifest);
			return null;
		}
		// stagefileは譜面の内容に影響しないため譜面キャッシュの条件からは除外する
		JsonValue options = payload.get("options");
		if (options == null
				|| options.getBoolean("include_bga", true) != request.includeBga
				|| options.getBoolean("include_preview", true) != request.includePreview) {
			return null;
		}
		return parseResult(payload, request, cacheDir);
	}

	private Result parseResult(JsonValue message, Request request) throws ConversionException {
		String resultDir = message.getString("result_dir", "");
		if (resultDir.isEmpty()) {
			throw new ConversionException("The converter reported an empty result directory");
		}
		return parseResult(message, request, Paths.get(resultDir).toAbsolutePath().normalize());
	}

	/**
	 * @param directory 譜面が実際に置かれているディレクトリ。キャッシュから読み込む場合は
	 *                  マニフェストの{@code result_dir}が移動前の場所を指しているため、
	 *                  キャッシュディレクトリを明示的に渡す
	 */
	private Result parseResult(JsonValue message, Request request, Path directory) throws ConversionException {
		List<Chart> charts = new ArrayList<Chart>();
		JsonValue chartArray = message.get("charts");
		if (chartArray != null) {
			for (JsonValue entry = chartArray.child; entry != null; entry = entry.next) {
				String name = entry.getString("name", "");
				if (name.isEmpty()) {
					continue;
				}
				charts.add(new Chart(entry.getString("difficulty", ""), name, entry.getString("file", ""),
						entry.getInt("level", 0)));
			}
		}
		if (charts.isEmpty()) {
			throw new ConversionException("The converter produced no playable chart");
		}

		String title = "";
		String artist = "";
		String genre = "";
		JsonValue song = message.get("song");
		if (song != null) {
			title = song.getString("title", "");
			artist = song.getString("artist", "");
			genre = song.getString("genre", "");
		}

		if (!Files.isDirectory(directory)) {
			throw new ConversionException("The result directory does not exist: " + directory);
		}
		return new Result(directory, charts, title, artist, genre, request.songIdDisplay);
	}

	private static JsonValue parseLine(String line) {
		String trimmed = line.trim();
		if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
			if (!trimmed.isEmpty()) {
				converterLogger.info(trimmed);
			}
			return null;
		}
		try {
			return new JsonReader().parse(trimmed);
		} catch (RuntimeException e) {
			converterLogger.info(trimmed);
			return null;
		}
	}

	private Thread startWatchdog(final Process process, final int timeoutSeconds) {
		Thread watchdog = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
						logger.error("iidx2bms conversion timed out after {} seconds", timeoutSeconds);
						process.destroyForcibly();
					}
				} catch (InterruptedException e) {
					// 正常終了
				}
			}
		}, "iidx2bms-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		return watchdog;
	}

	/**
	 * Pythonインタプリタを検出する。結果はインスタンス内でキャッシュする
	 *
	 * @return 起動コマンド。見つからなければnull
	 */
	public List<String> resolveInterpreter(Request request) {
		if (interpreterResolved) {
			return interpreter;
		}
		synchronized (this) {
			if (interpreterResolved) {
				return interpreter;
			}
			for (List<String> candidate : candidates(request.projectRoot)) {
				if (isUsable(candidate)) {
					logger.info("iidx2bms python interpreter: {}", candidate);
					interpreter = candidate;
					break;
				}
			}
			interpreterResolved = true;
			return interpreter;
		}
	}

	private static List<List<String>> candidates(String projectRoot) {
		List<List<String>> candidates = new ArrayList<List<String>>();
		if (projectRoot != null && !projectRoot.isEmpty()) {
			Path root = Paths.get(projectRoot);
			// iidx2bms同梱の仮想環境を最優先する
			Path windows = root.resolve(".venv").resolve("Scripts").resolve("python.exe");
			Path posix = root.resolve(".venv").resolve("bin").resolve("python");
			if (Files.isRegularFile(windows)) {
				candidates.add(Arrays.asList(windows.toString()));
			}
			if (Files.isRegularFile(posix)) {
				candidates.add(Arrays.asList(posix.toString()));
			}
		}
		candidates.add(Arrays.asList("py", "-3"));
		candidates.add(Arrays.asList("python"));
		candidates.add(Arrays.asList("python3"));
		return candidates;
	}

	private static boolean isUsable(List<String> command) {
		List<String> probe = new ArrayList<String>(command);
		probe.add("-c");
		probe.add("import ifstools");
		ProcessBuilder builder = new ProcessBuilder(probe);
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		Process process = null;
		try {
			process = builder.start();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (process != null) {
				process.destroyForcibly();
			}
			return false;
		}
	}
}
