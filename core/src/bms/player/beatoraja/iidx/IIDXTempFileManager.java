package bms.player.beatoraja.iidx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IIDX譜面の一時変換で使用するファイル配置を管理する。
 *
 * <pre>
 * %TEMP%/lr2oraja_iidx/
 *   cli/cli_convert.py   - jarから展開した変換ブリッジスクリプト
 *   cache/&lt;song_id&gt;/      - 変換済みの譜面(永続、サイズ上限あり)
 *   session/&lt;request&gt;/    - 変換中の作業ディレクトリ(起動時に全削除)
 * </pre>
 *
 * @author endlessdream
 */
public class IIDXTempFileManager {

	private static final Logger logger = LoggerFactory.getLogger(IIDXTempFileManager.class);

	private static final String BASE_DIR_NAME = "lr2oraja_iidx";
	private static final String CLI_DIR_NAME = "cli";
	private static final String CACHE_DIR_NAME = "cache";
	private static final String SESSION_DIR_NAME = "session";
	private static final String BRIDGE_RESOURCE = "resources/iidx2bms/cli_convert.py";
	private static final String BRIDGE_FILE_NAME = "cli_convert.py";

	private final Path baseDir;
	private final Path cliDir;
	private final Path cacheDir;
	private final Path sessionDir;

	private Path bridgeScript;
	private boolean prepared;

	public IIDXTempFileManager() {
		this(Paths.get(System.getProperty("java.io.tmpdir", "."), BASE_DIR_NAME));
	}

	public IIDXTempFileManager(Path baseDir) {
		this.baseDir = baseDir.toAbsolutePath().normalize();
		this.cliDir = this.baseDir.resolve(CLI_DIR_NAME);
		this.cacheDir = this.baseDir.resolve(CACHE_DIR_NAME);
		this.sessionDir = this.baseDir.resolve(SESSION_DIR_NAME);
	}

	public Path getBaseDir() {
		return baseDir;
	}

	public Path getCacheDir() {
		return cacheDir;
	}

	public Path getSessionDir() {
		return sessionDir;
	}

	/**
	 * ブリッジスクリプトのパス。{@link #prepare()} 呼び出し後に有効になる
	 */
	public Path getBridgeScript() {
		return bridgeScript;
	}

	/**
	 * ディレクトリを用意し、jarからブリッジスクリプトを展開して前回の作業ディレクトリを破棄する。
	 * 一時ディレクトリが使えない環境ではfalseを返し、呼び出し側で機能を無効化する。
	 */
	public boolean prepare() {
		if (prepared) {
			return true;
		}
		try {
			Files.createDirectories(cliDir);
			Files.createDirectories(cacheDir);
			clearSession();
			Files.createDirectories(sessionDir);

			Path script = cliDir.resolve(BRIDGE_FILE_NAME);
			try (InputStream in = IIDXTempFileManager.class.getClassLoader().getResourceAsStream(BRIDGE_RESOURCE)) {
				if (in == null) {
					logger.error("iidx2bms bridge script is missing from the classpath: {}", BRIDGE_RESOURCE);
					return false;
				}
				Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
			}
			bridgeScript = script;
			prepared = true;
			return true;
		} catch (IOException e) {
			logger.error("Failed to prepare the IIDX temporary directory: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * 指定した楽曲のキャッシュディレクトリを返す(存在しない場合はnull)
	 */
	public Path getCachedSongDir(String songIdDisplay) {
		Path dir = cacheDir.resolve(songIdDisplay);
		return Files.isDirectory(dir) ? dir : null;
	}

	/**
	 * 変換用の作業ディレクトリを新規作成して返す。同じ楽曲を連続して変換しても衝突しない
	 */
	public Path createSessionDir(String songIdDisplay) throws IOException {
		Files.createDirectories(sessionDir);
		Path dir = sessionDir.resolve(songIdDisplay + "_" + System.nanoTime());
		Files.createDirectories(dir);
		return dir;
	}

	/**
	 * 変換結果を作業ディレクトリからキャッシュへ移動する。移動に失敗した場合は元の位置を返す
	 */
	public Path promoteToCache(Path convertedDir, String songIdDisplay) {
		if (convertedDir == null || !Files.isDirectory(convertedDir)) {
			return convertedDir;
		}
		try {
			Files.createDirectories(cacheDir);
			Path target = cacheDir.resolve(songIdDisplay);
			if (Files.exists(target)) {
				deleteRecursively(target);
			}
			try {
				Files.move(convertedDir, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailure) {
				// ATOMIC_MOVEは同一ボリュームでしか成立しないため、通常移動へ退避する
				copyRecursively(convertedDir, target);
				deleteRecursively(convertedDir);
			}
			return target;
		} catch (IOException e) {
			logger.error("Failed to move the converted chart into the cache: {}", e.getMessage());
			return convertedDir;
		}
	}

	/**
	 * 作業ディレクトリを破棄する。変換に失敗した場合の後始末に使う
	 */
	public void discardSessionDir(Path dir) {
		if (dir == null) {
			return;
		}
		if (dir.startsWith(sessionDir)) {
			deleteRecursively(dir);
		}
	}

	/**
	 * 作業ディレクトリをすべて削除する
	 */
	public void clearSession() {
		deleteRecursively(sessionDir);
	}

	/**
	 * キャッシュ合計サイズが上限を超えている場合、古いものから削除する
	 *
	 * @param maxSizeMB 上限(MB)。0以下なら何もしない
	 */
	public void enforceCacheLimit(long maxSizeMB) {
		if (maxSizeMB <= 0 || !Files.isDirectory(cacheDir)) {
			return;
		}
		long limit = maxSizeMB * 1024L * 1024L;
		List<Path> entries = new ArrayList<Path>();
		try (Stream<Path> stream = Files.list(cacheDir)) {
			stream.filter(Files::isDirectory).forEach(entries::add);
		} catch (IOException e) {
			logger.error("Failed to inspect the IIDX cache directory: {}", e.getMessage());
			return;
		}

		long total = 0;
		List<Path> sorted = new ArrayList<Path>(entries);
		for (Path entry : sorted) {
			total += directorySize(entry);
		}
		if (total <= limit) {
			return;
		}

		sorted.sort(new Comparator<Path>() {
			@Override
			public int compare(Path a, Path b) {
				try {
					return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
				} catch (IOException e) {
					return 0;
				}
			}
		});
		for (Path entry : sorted) {
			if (total <= limit) {
				break;
			}
			long size = directorySize(entry);
			if (deleteRecursively(entry)) {
				total -= size;
				logger.info("Evicted IIDX cache entry {} ({} bytes)", entry.getFileName(), size);
			}
		}
	}

	public static long directorySize(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return 0;
		}
		final long[] size = new long[1];
		try (Stream<Path> stream = Files.walk(dir)) {
			stream.filter(Files::isRegularFile).forEach(p -> {
				try {
					size[0] += Files.size(p);
				} catch (IOException e) {
					// 計測できないファイルは0として扱う
				}
			});
		} catch (IOException e) {
			return size[0];
		}
		return size[0];
	}

	public static boolean deleteRecursively(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return false;
		}
		List<Path> paths = new ArrayList<Path>();
		try (Stream<Path> stream = Files.walk(dir)) {
			stream.forEach(paths::add);
		} catch (IOException e) {
			logger.error("Failed to walk {}: {}", dir, e.getMessage());
			return false;
		}
		// 深い方から消さないとディレクトリを削除できない
		paths.sort(Comparator.reverseOrder());
		boolean success = true;
		for (Path path : paths) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				logger.error("Failed to delete {}: {}", path, e.getMessage());
				success = false;
			}
		}
		return success;
	}

	private static void copyRecursively(Path source, Path target) throws IOException {
		try (Stream<Path> stream = Files.walk(source)) {
			for (Path path : (Iterable<Path>) stream::iterator) {
				Path destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
