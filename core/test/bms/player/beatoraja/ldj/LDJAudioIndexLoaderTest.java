package bms.player.beatoraja.ldj;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 直读音频索引加载器与单条目缓存的单元测试。
 *
 * <p>合成 JSON 覆盖正常解析、long 偏移、缺失/损坏/版本不符/非 direct 的降级，
 * 以及缓存的命中与换出；真实样本验证由环境变量 LDJ_TEST_SAMPLES 门控。</p>
 *
 * @author endlessdream
 */
class LDJAudioIndexLoaderTest {

	@TempDir
	Path tempDir;

	private Path writeIndex(String name, String json) throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve(name));
		Files.write(dir.resolve("ldj_audio.json"), json.getBytes(StandardCharsets.UTF_8));
		return dir;
	}

	private static String index(String audioMode, int version, String wav) {
		return "{"
				+ "\"index_version\":" + version + ","
				+ "\"audio_mode\":\"" + audioMode + "\","
				+ "\"song_id\":\"01009\","
				+ "\"containers\":[{\"path\":\"01009.ifs\"},{\"path\":\"01009-1.ifs\"}],"
				+ "\"wav\":" + wav
				+ "}";
	}

	@Test
	void parsesValidIndex() throws IOException {
		String wav = "{\"0001.wav\":[0,31547392,26944],\"0002.wav\":[0,31574336,12288],\"0003.wav\":[1,1048576,8192]}";
		LDJAudioIndex index = LDJAudioIndexLoader.load(writeIndex("valid", index("direct", 1, wav)));

		assertNotNull(index);
		assertEquals(1, index.getIndexVersion());
		assertEquals("direct", index.getAudioMode());
		assertEquals("01009", index.getSongId());
		assertEquals(2, index.getContainers().size());
		assertEquals(3, index.getWavCount());

		LDJAudioIndex.WavEntry first = index.getWav("0001.wav");
		assertNotNull(first);
		assertEquals(0, first.containerIndex);
		assertEquals(31547392L, first.offset);
		assertEquals(26944L, first.size);

		assertEquals(1, index.getWav("0003.wav").containerIndex);
		assertNull(index.getWav("9999.wav"));
	}

	@Test
	void parsesOffsetsBeyondIntRangeAsLong() throws IOException {
		// 偏移可能超过 2^31-1，必须按 long 读取
		String wav = "{\"0001.wav\":[0,4000000000,123]}";
		LDJAudioIndex index = LDJAudioIndexLoader.load(writeIndex("big", index("direct", 1, wav)));
		assertNotNull(index);
		assertEquals(4000000000L, index.getWav("0001.wav").offset);
	}

	@Test
	void returnsNullWhenIndexMissing() {
		assertNull(LDJAudioIndexLoader.load(tempDir.resolve("empty")));
	}

	@Test
	void returnsNullWhenJsonCorrupt() throws IOException {
		assertNull(LDJAudioIndexLoader.load(writeIndex("corrupt", "{not valid json")));
	}

	@Test
	void returnsNullWhenVersionMismatch() throws IOException {
		assertNull(LDJAudioIndexLoader.load(writeIndex("version", index("direct", 2, "{\"0001.wav\":[0,0,1]}"))));
	}

	@Test
	void returnsNullWhenNotDirect() throws IOException {
		assertNull(LDJAudioIndexLoader.load(writeIndex("extract", index("extract", 1, "{\"0001.wav\":[0,0,1]}"))));
	}

	@Test
	void returnsNullWhenWavEntryMalformed() throws IOException {
		// 容器下标越界、偏移为负、字段不足都应视为损坏
		String[] bad = {
				"{\"0001.wav\":[2,0,1]}",
				"{\"0001.wav\":[0,-1,1]}",
				"{\"0001.wav\":[0,0]}",
		};
		for (String wav : bad) {
			assertNull(LDJAudioIndexLoader.load(writeIndex("bad", index("direct", 1, wav))));
		}
	}

	@Test
	void cacheReturnsCachedResultAndEvicts() throws IOException {
		String wav = "{\"0001.wav\":[0,0,1]}";
		Path dirA = writeIndex("a", index("direct", 1, wav));
		LDJAudioIndexCache cache = new LDJAudioIndexCache();

		LDJAudioIndex a = cache.get(dirA);
		assertNotNull(a);

		// 删除索引后仍命中缓存，证明没有重新读盘
		Files.delete(dirA.resolve("ldj_audio.json"));
		assertSame(a, cache.get(dirA));

		// 换目录会替换缓存；无索引的目录加载为 null 且同样缓存
		assertNull(cache.get(tempDir.resolve("b")));

		// 重新建回索引，因已被换出而重新加载
		Files.write(dirA.resolve("ldj_audio.json"), index("direct", 1, wav).getBytes(StandardCharsets.UTF_8));
		assertNotNull(cache.get(dirA));
	}

	@Test
	void loadsRealConverterSamples() {
		String samplesDir = System.getenv("LDJ_TEST_SAMPLES");
		assumeTrue(samplesDir != null && !samplesDir.isEmpty(), "LDJ_TEST_SAMPLES 未设置，跳过真实样本验证");
		assertSampleCount(samplesDir, "01009", 38);
		assertSampleCount(samplesDir, "01000", 145);
		// 注意：01007 端到端优先选根目录 01007.ifs，索引为 429 条；
		// 423 是第 2 步在解包目录 01007/01007.2dx 上测得的数，待确认以哪个为准。
		assertSampleCount(samplesDir, "01007", 429);
	}

	private static void assertSampleCount(String samplesDir, String songId, int expected) {
		Path dir = locateSampleDir(samplesDir, songId);
		assertNotNull(dir, songId + " 样本目录不存在");
		LDJAudioIndex index = LDJAudioIndexLoader.load(dir);
		assertNotNull(index, songId + " 索引加载失败");
		assertEquals(expected, index.getWavCount(), songId + " 键音数不符");
	}

	/**
	 * 在样本目录里按 song_id_display 前缀定位结果目录。
	 * 转换器把结果目录命名为「id 标题」，如「01009 22DUNK」。
	 */
	private static Path locateSampleDir(String samplesDir, String songId) {
		File root = Paths.get(samplesDir).toFile();
		File[] entries = root.listFiles();
		if (entries != null) {
			for (File entry : entries) {
				if (entry.isDirectory() && entry.getName().startsWith(songId)) {
					return entry.toPath();
				}
			}
		}
		return null;
	}
}
