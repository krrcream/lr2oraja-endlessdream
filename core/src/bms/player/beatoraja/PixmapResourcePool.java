package bms.player.beatoraja;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import bms.player.beatoraja.song.Resource;

/**
 * Pixmapリソースプール
 * 
 * @author exch
 */
public class PixmapResourcePool extends ResourcePool<String, Pixmap> {
	private static Logger logger = LoggerFactory.getLogger(PixmapResourcePool.class);

	public PixmapResourcePool() {
		super(1);
	}
	
	public PixmapResourcePool(int maxgen) {
		super(maxgen);
	}
	
	@Override
	protected Pixmap load(String path) {
		final Pixmap pixmap = loadPicture(path);
		return pixmap != null ? convert(pixmap) : null;
	}

	/**
	 * Pixmapをload時に変換する。
	 *
	 * @param pixmap
	 * @return
	 */
	protected Pixmap convert(Pixmap pixmap) {
		return pixmap;
	}

	@Override
	protected void dispose(Pixmap resource) {
		resource.dispose();
	}

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージ。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static Pixmap loadPicture(String path) {
		File f = new File(path);
		if(!f.isFile()) {
			return null;
		}

		if(path.endsWith(".cim")) {
			try {
				return PixmapIO.readCIM(Gdx.files.internal(path));
			} catch (Throwable e) {
				logger.warn("BGAファイル読み込み失敗。{}", e.getMessage());
				return null;
			}
		}

		try (InputStream in = new FileInputStream(f)) {
			return decode(in, path);
		} catch (Throwable e) {
			logger.warn("BGAファイル読み込み失敗。{}", e.getMessage());
			return null;
		}
	}

	/**
	 * 用 Java 侧的 ImageIO 解码图片。
	 * 不走 libgdx 的 native stb_image：它遇到畸形或非常规的图片是 C 层 assert，
	 * 会弹出 CRT 断言框并把这个线程卡死，Java 侧的 try/catch 拦不住，只能强退游戏。
	 * 这里解码失败一律当作「这张图不存在」，记一条日志后返回 null，游戏照常继续。
	 */
	private static Pixmap decode(InputStream in, String name) {
		try {
			BufferedImage image = ImageIO.read(in);
			if (image == null) {
				logger.warn("画像デコード失敗(スキップ) : {}", name);
				return null;
			}
			return toPixmap(image);
		} catch (Throwable e) {
			logger.warn("画像デコード失敗(スキップ) : {} - {}", name, e.getMessage());
			return null;
		}
	}

	/**
	 * BufferedImage 整块转成 RGBA8888 的 Pixmap。
	 * 逐像素 drawPixel 对 640x480 以上的图太慢，这里直接写 Pixmap 的像素缓冲区。
	 */
	private static Pixmap toPixmap(BufferedImage image) {
		final int width = image.getWidth();
		final int height = image.getHeight();
		final Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
		final int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
		final ByteBuffer buffer = pixmap.getPixels();
		buffer.clear();
		for (int pixel : argb) {
			buffer.put((byte) (pixel >> 16));
			buffer.put((byte) (pixel >> 8));
			buffer.put((byte) pixel);
			buffer.put((byte) (pixel >>> 24));
		}
		buffer.position(0);
		return pixmap;
	}

	public Pixmap get(Resource resource) {
		return get(resource.key(), () -> {
			Pixmap pixmap = loadPicture(resource);
			return pixmap != null ? convert(pixmap) : null;
		});
	}

	public static Pixmap loadPicture(Resource resource) {
		try {
			if (resource.path().isPresent()) return loadPicture(resource.path().get().toString());
			byte[] data = resource.readAllBytes();
			return decode(new ByteArrayInputStream(data), resource.filename());
		} catch (Throwable error) {
			logger.warn("BGA resource load failed: {}", resource.filename());
			return null;
		}
	}
}
