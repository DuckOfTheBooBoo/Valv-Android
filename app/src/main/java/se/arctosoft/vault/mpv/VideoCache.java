/*
 * Valv-Android
 * Copyright (C) 2024 Arctosoft AB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/.
 */

package se.arctosoft.vault.mpv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;

import se.arctosoft.vault.data.GalleryFile;
import se.arctosoft.vault.data.Password;
import se.arctosoft.vault.encryption.Encryption;
import se.arctosoft.vault.utils.FileStuff;

/**
 * libmpv reads media through its own IO layer and cannot consume Valv's on-the-fly
 * decrypting {@code CipherInputStream} (which is forward-only and not seekable).
 * <p>
 * To play an encrypted video we therefore decrypt it into the app's private cache
 * directory and hand the plaintext file path to mpv. The plaintext only exists while
 * the video viewer is open: {@link #clearAll(Context)} wipes everything when the
 * viewer is closed, and files are stored in app-private storage that other apps
 * cannot read.
 */
public class VideoCache {
    private static final String TAG = "VideoCache";
    private static final String CACHE_DIR = "mpv_video";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, File> READY_FILES = new ConcurrentHashMap<>();

    public interface Callback {
        void onReady(@NonNull File file);

        void onError(@NonNull Exception e);
    }

    private VideoCache() {
    }

    @NonNull
    private static File cacheDir(@NonNull Context context) {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static String keyFor(@NonNull GalleryFile galleryFile) {
        return String.valueOf(galleryFile.getUri());
    }

    /**
     * Decrypt {@code galleryFile} into a cache file, invoking {@code callback} on the
     * main thread. If the file was already decrypted during this viewer session the
     * cached copy is returned immediately.
     */
    public static void getDecryptedFile(@NonNull Context context, @NonNull GalleryFile galleryFile, @NonNull Callback callback) {
        final String key = keyFor(galleryFile);
        File existing = READY_FILES.get(key);
        if (existing != null && existing.exists() && existing.length() > 0) {
            callback.onReady(existing);
            return;
        }
        final Context appContext = context.getApplicationContext();
        final Uri uri = galleryFile.getUri();
        final int version = galleryFile.getVersion();
        final String extension = FileStuff.getExtensionOrDefault(galleryFile);
        new Thread(() -> {
            File outFile = new File(cacheDir(appContext), Math.abs(key.hashCode()) + (extension != null ? extension : ".tmp"));
            Encryption.Streams streams = null;
            try (InputStream fileStream = appContext.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(outFile)) {
                streams = Encryption.getCipherInputStream(fileStream, Password.getInstance().getPassword(), false, version);
                InputStream in = streams.getInputStream();
                byte[] buffer = new byte[1024 * 32];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                READY_FILES.put(key, outFile);
                MAIN_HANDLER.post(() -> callback.onReady(outFile));
            } catch (Exception e) {
                Log.e(TAG, "getDecryptedFile: failed to decrypt video", e);
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                MAIN_HANDLER.post(() -> callback.onError(e));
            } finally {
                if (streams != null) {
                    streams.close();
                }
            }
        }).start();
    }

    /**
     * Delete all decrypted plaintext videos. Call this when the video viewer is torn
     * down so plaintext never lingers on disk.
     */
    public static void clearAll(@Nullable Context context) {
        READY_FILES.clear();
        if (context == null) {
            return;
        }
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
