package bms.player.beatoraja.ldj;

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
 * 启动通往 iidx2bms 的桥接脚本({@code cli_convert.py})，将 LDJ 谱面转换为 BMS
 *
 * <p>脚本以每行一个 JSON 的形式向 stdout 输出进度和结果，转换器本体的日志输出到 stderr，
 * 因此无法按 JSON 解析的行直接跳过</p>
 *
 * @author endlessdream
 */
public class LDJConversionService {

	private static final Logger logger = LoggerFactory.getLogger(LDJConversionService.class);

	private static final Logger converterLogger = LoggerFactory.getLogger("iidx2bms");

	/** 整个转换的默认超时时间(秒) */
	public static final int DEFAULT_TIMEOUT_SECONDS = 180;

	/** 写入转换结果目录的清单文件名 */
	public static final String MANIFEST_FILE = "manifest.json";

	/** 每个缓存目录中随直读音频一同生成的索引文件名 */
	public static final String AUDIO_INDEX_FILE = "ldj_audio.json";

	/** 音频索引的结构版本，必须与清单中的 index_version 一致 */
	public static final int AUDIO_INDEX_VERSION = 1;

	/** 直读音轨：不导出 WAV，运行时按索引从容器中切片 */
	public static final String AUDIO_MODE_DIRECT = "direct";

	/** 预提取音轨：导出全部 WAV，运行时读取 WAV 文件 */
	public static final String AUDIO_MODE_EXTRACT = "extract";

	/*
	 * 直读音频的数据契约（index_version = 1）
	 *
	 * 每首曲目的缓存目录中最多有一个 ldj_audio.json，只有 audio_mode = direct 时才会生成。
	 * 文件内容如下：
	 *
	 * {
	 *   "index_version": 1,
	 *   "audio_mode": "direct",
	 *   "song_id": "12345",
	 *   "containers": [
	 *     { "path": "12345.ifs" },
	 *     { "path": "12345-1.ifs" }
	 *   ],
	 *   "wav": {
	 *     "0001.wav": [0, 31547392, 26944],
	 *     "0002.wav": [0, 31574336, 12288],
	 *     "0003.wav": [1, 1048576, 8192]
	 *   }
	 * }
	 *
	 * 各字段的约定：
	 * - index_version：索引结构版本，必须与清单 options.index_version 相同。
	 * - audio_mode：固定为 direct；本文件不用于 extract 模式。
	 * - song_id：仅用于排查问题，运行时不做校验。
	 * - containers[].path：相对 LDJ 音频根的路径，可以指向 .ifs，也可以指向解包后的 .2dx；
	 *   不带大小、时间戳或哈希，因此缓存目录整体搬家后索引仍然有效。
	 * - wav：键是 BME 中 #WAVxx 使用的精确文件名，值是
	 *   [容器下标, 容器内绝对字节偏移, 字节数]；条目从 1 开始编号，
	 *   文件名宽度沿用现有提取逻辑的 max(4, 声明键音数的十进制位数)。
	 *
	 * 生成与读取的约定：
	 * - 索引由 Python 转换器生成，Java 侧只读，从不改写。
	 * - 写入顺序固定为「先索引、后清单」：索引先写到同目录的临时文件，
	 *   原子重命名成 ldj_audio.json 后才写 manifest.json。
	 *   因此清单声明 direct 时，索引一定已经完整可见。
	 * - 任何一个环节失败都回退为 extract，并在清单中如实记录最终模式，
	 *   不会留下声明 direct 却没有索引的缓存。
	 * - 失效条件是整曲重转：清单 options.audio_mode 或 options.index_version
	 *   缺失、取值不符，或者 direct 模式下索引缺失、版本不符，
	 *   都按普通缓存未命中处理。
	 */

	private final LDJTempFileManager tempFiles;

	/** 所使用的 Python 解释器的启动命令(例: ["py", "-3"])，未检测到则为 null */
	private List<String> interpreter;

	private boolean interpreterResolved;

	/** 正在运行的进程，用于取消 */
	private volatile Process currentProcess;
	private volatile boolean cancelled;

	public LDJConversionService(LDJTempFileManager tempFiles) {
		this.tempFiles = tempFiles;
	}

	/**
	 * 单个谱面的转换结果，{@code file}是清单所报告的绝对路径，
	 * 移动到缓存后会失效，因此需通过{@link #resolve(Path)}解析
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
		 * 以转换目录为基准的实际谱面文件路径
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
					// 位于其他驱动器上时按文件名解析
				}
			}
			return baseDir.resolve(name);
		}
	}

	/**
	 * 转换结果，{@code resultDir}在移动后不可用，因此同时保留已确定的存放位置
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
	 * 转换请求
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
	 * 进度通知目标，由调用方线程调用
	 */
	public interface ProgressListener {
		void onProgress(int percent, String stage);

		void onWarning(String message);
	}

	/**
	 * 表示转换失败，消息可直接通知给用户
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
	 * 未找到 Python 解释器时的错误
	 */
	public static class InterpreterNotFoundException extends ConversionException {
		private static final long serialVersionUID = 1L;

		public InterpreterNotFoundException(String message) {
			super(message);
		}
	}

	/**
	 * 中断进行中的转换，强制结束进程
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
	 * 执行转换，此调用会阻塞，因此调用方需将其放到其他线程上
	 */
	public Result convert(Request request, ProgressListener listener) throws ConversionException {
		if (tempFiles.getBridgeScript() == null && !tempFiles.prepare()) {
			throw new ConversionException("LDJ temporary directory is unavailable");
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
		// LDJPlus 变体固定直读；索引生成失败时转换器会在同一进程内回退 extract
		command.add("--audio-mode");
		command.add(AUDIO_MODE_DIRECT);

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
	 * 读取已缓存的转换结果，若清单不存在或已损坏，
	 * 或者转换条件与当前请求不一致，则返回 null
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
		// stagefile 不影响谱面内容，因此从谱面缓存条件中排除
		// p0_patch 缺失说明该缓存是补丁层支持之前写入的，
		// 其中的谱面可能少了 SPL/DPL，一律视为过期；
		// 这里只判断键是否存在，不判断补丁层文件是否存在，
		// 否则降级转换（p0_patch=false）的缓存每次都会被判定为过期
		// dir_fallback、nonstd_ifs_accept、split_audio_merge 同理：
		// 缺失说明该缓存是各自支持之前写入的
		// audio_mode、index_version 缺失说明该缓存是直读改造之前写入的；
		// 两者由转换器同时写入，任何一个不符都按普通缓存未命中处理，整曲重转
		// 直读缓存还要求索引存在且版本一致，索引被删除或改动时同样重转
		JsonValue options = payload.get("options");
		if (options == null) {
			return null;
		}
		String audioMode = options.getString("audio_mode", "");
		if (options.getBoolean("include_bga", true) != request.includeBga
				|| options.getBoolean("include_preview", true) != request.includePreview
				|| options.get("p0_patch") == null
				|| options.get("dir_fallback") == null
				|| options.get("nonstd_ifs_accept") == null
				|| options.get("split_audio_merge") == null
				|| (!AUDIO_MODE_DIRECT.equals(audioMode) && !AUDIO_MODE_EXTRACT.equals(audioMode))
				|| options.getInt("index_version", -1) != AUDIO_INDEX_VERSION
				|| (AUDIO_MODE_DIRECT.equals(audioMode) && !hasValidAudioIndex(cacheDir))) {
			return null;
		}
		return parseResult(payload, request, cacheDir);
	}

	/**
	 * 判断直读缓存附带的音频索引是否可用。
	 *
	 * <p>契约要求先写完索引再写清单，正常生成的缓存不会缺少索引；
	 * 这里委托 {@link LDJAudioIndexLoader} 完整解析，防止索引被删除、
	 * 损坏或版本改动后继续使用。读取失败一律当作没有索引，
	 * 交给上层按缓存未命中重转。</p>
	 */
	private static boolean hasValidAudioIndex(Path cacheDir) {
		return LDJAudioIndexLoader.load(cacheDir) != null;
	}

	private Result parseResult(JsonValue message, Request request) throws ConversionException {
		String resultDir = message.getString("result_dir", "");
		if (resultDir.isEmpty()) {
			throw new ConversionException("The converter reported an empty result directory");
		}
		return parseResult(message, request, Paths.get(resultDir).toAbsolutePath().normalize());
	}

	/**
	 * @param directory 谱面实际所在的目录，从缓存读取时，
	 *                  由于清单的{@code result_dir}指向移动前的位置，
	 *                  需显式传入缓存目录
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
					// 正常结束
				}
			}
		}, "iidx2bms-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		return watchdog;
	}

	/**
	 * 检测 Python 解释器，结果在实例内缓存
	 *
	 * @return 启动命令，未找到则为 null
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
			// 优先使用 iidx2bms 自带的虚拟环境
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
