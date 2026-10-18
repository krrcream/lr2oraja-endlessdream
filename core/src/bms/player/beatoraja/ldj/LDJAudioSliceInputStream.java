package bms.player.beatoraja.ldj;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 只读容器文件一段字节区间的有界输入流（LDJ 直读用）。
 *
 * <p>只读 [offset, offset+size) 这段字节，读满 size 后返回 EOF；
 * close 只关闭本次打开的通道，不影响容器文件本身。</p>
 *
 * @author endlessdream
 */
public final class LDJAudioSliceInputStream extends InputStream {

	/** 已打开的容器通道 */
	private final FileChannel channel;

	/** 区间字节数 */
	private final long size;

	/** 相对区间起点已读的字节数 */
	private long position;

	public LDJAudioSliceInputStream(Path container, long offset, long size) throws IOException {
		this.channel = FileChannel.open(container, StandardOpenOption.READ);
		this.channel.position(offset);
		this.size = size;
	}

	@Override
	public int read() throws IOException {
		if (position >= size) {
			return -1;
		}
		ByteBuffer one = ByteBuffer.allocate(1);
		int n = channel.read(one);
		if (n < 0) {
			return -1;
		}
		position += n;
		return one.get(0) & 0xff;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (position >= size) {
			return -1;
		}
		int remaining = (int) Math.min(len, size - position);
		ByteBuffer buf = ByteBuffer.wrap(b, off, remaining);
		int n = channel.read(buf);
		if (n < 0) {
			return -1;
		}
		position += n;
		return n;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
