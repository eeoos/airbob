import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public class GlobalBImageDecode {
    public static void main(String[] args) throws Exception {
        byte[] input = System.in.readNBytes(8 * 1024 * 1024 + 1);
        if (input.length == 0 || input.length > 8 * 1024 * 1024) throw new IllegalArgumentException();
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException();
            ImageReader reader = readers.next();
            try {
                reader.addIIOReadWarningListener((source, warning) -> { throw new IllegalArgumentException(); });
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 24_000_000) throw new IllegalArgumentException();
                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) throw new IllegalArgumentException();
                System.out.printf("{\"fullyDecoded\":true,\"width\":%d,\"height\":%d}%n", width, height);
            } finally {
                reader.dispose();
            }
        }
    }
}
