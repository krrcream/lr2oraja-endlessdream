package bms.player.beatoraja.ldj;

import java.nio.file.Path;

/**
 * 直读音频索引的单条目缓存。
 *
 * <p>同一时刻只保留最近访问的那一条索引，键为缓存目录的绝对路径。
 * 换到另一首曲目时加载新索引并替换旧条目；索引缺失（加载结果为 null）同样缓存，
 * 避免同一目录反复读文件。用 volatile 加双重检查保证线程安全。</p>
 *
 * @author endlessdream
 */
public final class LDJAudioIndexCache {

	/** 当前缓存的键（缓存目录绝对路径） */
	private volatile Path cachedDir;

	/** 当前缓存的值，加载失败时与 {@link #cachedDir} 对应地缓存 null */
	private volatile LDJAudioIndex cachedIndex;

	/**
	 * 返回指定缓存目录的音频索引，目录未缓存时加载并替换旧条目。
	 *
	 * @param cacheDir 每首曲目的缓存目录，可为 null（此时返回 null）
	 * @return 解析成功返回索引，否则返回 null
	 */
	public LDJAudioIndex get(Path cacheDir) {
		if (cacheDir == null) {
			return null;
		}
		Path normalized = cacheDir.toAbsolutePath().normalize();
		if (normalized.equals(cachedDir)) {
			return cachedIndex;
		}
		synchronized (this) {
			// 双重检查：进入临界区后再次确认，避免重复加载同一目录
			if (normalized.equals(cachedDir)) {
				return cachedIndex;
			}
			LDJAudioIndex loaded = LDJAudioIndexLoader.load(cacheDir);
			// 先写值再发布键，保证读到新键的线程一定读到新值
			cachedIndex = loaded;
			cachedDir = normalized;
			return loaded;
		}
	}
}
