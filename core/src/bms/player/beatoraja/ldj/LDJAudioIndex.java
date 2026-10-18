package bms.player.beatoraja.ldj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直读音频索引（ldj_audio.json）的只读数据模型。
 *
 * <p>由转换器在 audio_mode=direct 时写入缓存目录，运行时只读。
 * 字段语义见 {@link LDJConversionService} 中的直读数据契约注释。
 * 对象一经构造即不可变，由 {@link LDJAudioIndexLoader} 负责构建。</p>
 *
 * @author endlessdream
 */
public final class LDJAudioIndex {

	/** 索引结构版本，须与 {@link LDJConversionService#AUDIO_INDEX_VERSION} 一致 */
	private final int indexVersion;

	/** 生成索引时的音频模式，直读索引恒为 {@link LDJConversionService#AUDIO_MODE_DIRECT} */
	private final String audioMode;

	/** 乐曲显示名，仅用于排障与自检，运行时不做校验 */
	private final String songId;

	/** 容器列表，路径相对 LDJ 音频根目录 */
	private final List<Container> containers;

	/** 键音文件名（如 0001.wav）到切片位置的映射，保持文件中的出现顺序 */
	private final Map<String, WavEntry> wav;

	LDJAudioIndex(int indexVersion, String audioMode, String songId, List<Container> containers,
			Map<String, WavEntry> wav) {
		this.indexVersion = indexVersion;
		this.audioMode = audioMode;
		this.songId = songId;
		this.containers = Collections.unmodifiableList(new ArrayList<Container>(containers));
		this.wav = Collections.unmodifiableMap(new LinkedHashMap<String, WavEntry>(wav));
	}

	public int getIndexVersion() {
		return indexVersion;
	}

	public String getAudioMode() {
		return audioMode;
	}

	public String getSongId() {
		return songId;
	}

	/** 返回容器列表（只读） */
	public List<Container> getContainers() {
		return containers;
	}

	/** 返回下标对应的容器路径（相对 LDJ 音频根目录），下标越界返回 null */
	public String getContainerPath(int index) {
		if (index < 0 || index >= containers.size()) {
			return null;
		}
		return containers.get(index).path;
	}

	/** 按文件名查找键音切片，不存在返回 null */
	public WavEntry getWav(String filename) {
		return wav.get(filename);
	}

	/** 键音条目数 */
	public int getWavCount() {
		return wav.size();
	}

	/** 单个容器路径条目 */
	public static final class Container {
		/** 相对 LDJ 音频根目录的路径 */
		public final String path;

		Container(String path) {
			this.path = path;
		}
	}

	/** 单个键音在容器文件中的切片位置 */
	public static final class WavEntry {
		/** 容器在 {@link LDJAudioIndex#getContainers()} 中的下标 */
		public final int containerIndex;

		/** 文件内绝对偏移（字节），可能超出 int 范围，用 long 保存 */
		public final long offset;

		/** 切片字节数 */
		public final long size;

		WavEntry(int containerIndex, long offset, long size) {
			this.containerIndex = containerIndex;
			this.offset = offset;
			this.size = size;
		}
	}
}
