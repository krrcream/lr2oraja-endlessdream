package bms.player.beatoraja.ldj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从缓存目录读取直读音频索引（ldj_audio.json）的无状态加载器。
 *
 * <p>任何加载失败——索引缺失、JSON 损坏、index_version 不符、audio_mode 非 direct——
 * 一律返回 null，由调用方当作「没有索引」处理，不把异常抛给上层。</p>
 *
 * @author endlessdream
 */
public final class LDJAudioIndexLoader {

	private static final Logger logger = LoggerFactory.getLogger(LDJAudioIndexLoader.class);

	private LDJAudioIndexLoader() {
	}

	/**
	 * 读取并解析指定缓存目录里的音频索引，失败返回 null。
	 *
	 * @param cacheDir 每首曲目的缓存目录，可为 null
	 * @return 解析成功返回索引，否则返回 null
	 */
	public static LDJAudioIndex load(Path cacheDir) {
		if (cacheDir == null) {
			return null;
		}
		Path index = cacheDir.resolve(LDJConversionService.AUDIO_INDEX_FILE);
		if (!Files.isRegularFile(index)) {
			return null;
		}
		JsonValue payload;
		try {
			payload = new JsonReader().parse(new String(Files.readAllBytes(index), StandardCharsets.UTF_8));
		} catch (IOException e) {
			logger.warn("iidx2bms: failed to read the audio index {}", index, e);
			return null;
		} catch (RuntimeException e) {
			logger.warn("iidx2bms: the audio index {} is not valid JSON", index);
			return null;
		}
		return parse(payload);
	}

	private static LDJAudioIndex parse(JsonValue payload) {
		if (payload == null || !payload.isObject()) {
			return null;
		}
		int indexVersion = payload.getInt("index_version", -1);
		if (indexVersion != LDJConversionService.AUDIO_INDEX_VERSION) {
			logger.warn("iidx2bms: audio index version {} does not match expected {}", indexVersion,
					LDJConversionService.AUDIO_INDEX_VERSION);
			return null;
		}
		String audioMode = payload.getString("audio_mode", "");
		if (!LDJConversionService.AUDIO_MODE_DIRECT.equals(audioMode)) {
			logger.warn("iidx2bms: audio index mode '{}' is not direct, ignoring index", audioMode);
			return null;
		}
		String songId = payload.getString("song_id", "");

		JsonValue containersValue = payload.get("containers");
		if (containersValue == null || !containersValue.isArray()) {
			return null;
		}
		List<LDJAudioIndex.Container> containers = new ArrayList<LDJAudioIndex.Container>();
		for (JsonValue c = containersValue.child; c != null; c = c.next) {
			String path = c.getString("path", null);
			if (path == null || path.isEmpty()) {
				return null;
			}
			containers.add(new LDJAudioIndex.Container(path));
		}

		JsonValue wavValue = payload.get("wav");
		if (wavValue == null || !wavValue.isObject()) {
			return null;
		}
		Map<String, LDJAudioIndex.WavEntry> wav = new LinkedHashMap<String, LDJAudioIndex.WavEntry>();
		for (JsonValue entry = wavValue.child; entry != null; entry = entry.next) {
			String filename = entry.name;
			if (filename == null || filename.isEmpty() || !entry.isArray() || entry.size < 3) {
				return null;
			}
			int containerIndex = entry.getInt(0);
			long offset = entry.getLong(1);
			long size = entry.getLong(2);
			if (containerIndex < 0 || containerIndex >= containers.size() || offset < 0 || size < 0) {
				return null;
			}
			wav.put(filename, new LDJAudioIndex.WavEntry(containerIndex, offset, size));
		}

		return new LDJAudioIndex(indexVersion, audioMode, songId, containers, wav);
	}
}
