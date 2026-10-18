package bms.player.beatoraja.ldj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 直读有界输入流的单元测试：验证只读 [offset, offset+size) 这一段字节，
 * 读满后返回 EOF，不受文件其余部分影响。
 *
 * @author endlessdream
 */
class LDJAudioSliceInputStreamTest {

	@TempDir
	Path tempDir;

	@Test
	void readsExactSliceAndReturnsEof() throws IOException {
		byte[] data = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
		Path file = tempDir.resolve("container.bin");
		Files.write(file, data);

		// 读 [4, 10) = "456789"
		try (LDJAudioSliceInputStream in = new LDJAudioSliceInputStream(file, 4, 6)) {
			byte[] got = in.readAllBytes();
			assertEquals("456789", new String(got, StandardCharsets.UTF_8));
			assertEquals(-1, in.read());
		}
	}

	@Test
	void readStopsAtSliceEndEvenIfFileLonger() throws IOException {
		byte[] data = new byte[100];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) i;
		}
		Path file = tempDir.resolve("container.bin");
		Files.write(file, data);

		try (LDJAudioSliceInputStream in = new LDJAudioSliceInputStream(file, 10, 5)) {
			byte[] buf = new byte[100];
			int n = in.read(buf, 0, 100); // 请求 100，但区间只有 5 字节
			assertEquals(5, n);
			assertEquals((byte) 10, buf[0]);
			assertEquals((byte) 14, buf[4]);
			assertEquals(-1, in.read(buf, 0, 100)); // 已到区间末尾
		}
	}

	@Test
	void singleByteReadWorks() throws IOException {
		byte[] data = "abcdef".getBytes(StandardCharsets.UTF_8);
		Path file = tempDir.resolve("container.bin");
		Files.write(file, data);

		try (LDJAudioSliceInputStream in = new LDJAudioSliceInputStream(file, 2, 2)) {
			assertEquals('c', in.read());
			assertEquals('d', in.read());
			assertEquals(-1, in.read());
		}
	}
}
