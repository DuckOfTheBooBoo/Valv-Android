package se.arctosoft.vault;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the migration from ExoPlayer/media3 to mpv (libmpv). Verifies that the mpv
 * integration is present and that the old ExoPlayer video pipeline is gone.
 */
public class VideoPlayerMigrationTest {
    private static final int MAX_PARENT_SEARCH_DEPTH = 4;

    @Test
    public void mpvPlayerIsUsedForVideoPlayback() throws Exception {
        Path projectRoot = findProjectRoot();

        Path mpvPlayer = projectRoot.resolve("app/src/main/java/se/arctosoft/vault/mpv/MpvPlayer.java");
        assertTrue("MpvPlayer wrapper should exist", Files.exists(mpvPlayer));
        assertTrue("MpvPlayer should use libmpv", readFile(mpvPlayer).contains("dev.jdtech.mpv.MPVLib"));

        Path viewer = projectRoot.resolve("app/src/main/java/se/arctosoft/vault/VideoViewerFragment.java");
        assertTrue("Vertical video viewer should exist", Files.exists(viewer));
        assertTrue("Viewer should drive the mpv player", readFile(viewer).contains("MpvPlayer"));
    }

    @Test
    public void exoPlayerIsRemovedFromGalleryPager() throws Exception {
        Path projectRoot = findProjectRoot();
        Path adapterPath = projectRoot.resolve("app/src/main/java/se/arctosoft/vault/adapters/GalleryPagerAdapter.java");
        assertTrue(Files.exists(adapterPath));
        String content = readFile(adapterPath);
        assertFalse("GalleryPagerAdapter should no longer reference ExoPlayer", content.contains("ExoPlayer"));
        assertFalse("GalleryPagerAdapter should no longer reference media3", content.contains("androidx.media3"));
    }

    private static String readFile(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < MAX_PARENT_SEARCH_DEPTH; i++) {
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
