package se.arctosoft.vault;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class DecoderFallbackTest {
    @Test
    public void galleryPagerEnablesDecoderFallback() throws Exception {
        Path projectRoot = findProjectRoot();
        Path adapterPath = projectRoot.resolve("app/src/main/java/se/arctosoft/vault/adapters/GalleryPagerAdapter.java");
        assertTrue(Files.exists(adapterPath));
        String content = Files.readString(adapterPath);
        assertTrue(content.contains("DefaultRenderersFactory"));
        assertTrue(content.contains("setEnableDecoderFallback(true)"));
        assertTrue(content.contains("setRenderersFactory(renderersFactory)"));
    }

    private static Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            if (Files.exists(current.resolve("app/src/main/java/se/arctosoft/vault/adapters/GalleryPagerAdapter.java"))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return Paths.get("").toAbsolutePath();
    }
}
