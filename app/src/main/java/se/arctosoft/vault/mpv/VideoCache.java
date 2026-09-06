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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * directory and hand the plaintext file path to mpv. Adjacent videos are decrypted
 * ahead of time ({@link #prefetch}) so that swiping to the next/previous clip does not
 * wait on a full decryption. In-flight decryptions are de-duplicated, so a prefetch and
 * a subsequent play request for the same file share one pass.
 * <p>
 * The plaintext only exists while the viewer is open: {@link #clearAll(Context)} wipes
 * everything when the viewer is closed, and files live in app-private storage that other
 * apps cannot read.
 */
public class VideoCache {
    private static final String TAG = "VideoCache";
    private static final String CACHE_DIR = "mpv_video";

    /** Keep at most this many decrypted clips on disk (current clip plus its neighbours). */
    private static final int MAX_CACHED = 5;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, File> READY_FILES = new ConcurrentHashMap<>();
    private static final Map<String, List<Callback>> IN_PROGRESS = new HashMap<>();
    private static final java.util.LinkedList<String> LRU = new java.util.LinkedList<>();

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
     * main thread. If it was already decrypted (or is being decrypted) during this viewer
     * session the cached copy is reused. Pass a {@code null} callback to only warm the
     * cache (see {@link #prefetch}).
     */
    public static void getDecryptedFile(@NonNull Context context, @NonNull GalleryFile galleryFile, @Nullable Callback callback) {
        final String key = keyFor(galleryFile);
        File existing = READY_FILES.get(key);
        if (existing != null && existing.exists() && existing.length() > 0) {
            if (callback != null) {
                callback.onReady(existing);
            }
            return;
        }
        synchronized (IN_PROGRESS) {
            List<Callback> waiting = IN_PROGRESS.get(key);
            if (waiting != null) {
                // A decryption for this file is already running; ride along with it.
                if (callback != null) {
                    waiting.add(callback);
                }
                return;
            }
            waiting = new ArrayList<>();
            if (callback != null) {
                waiting.add(callback);
            }
            IN_PROGRESS.put(key, waiting);
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
                byte[] buffer = new byte[1024 * 64];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                READY_FILES.put(key, outFile);
                evictOld(key);
                notifyWaiting(key, outFile, null);
            } catch (Exception e) {
                Log.e(TAG, "getDecryptedFile: failed to decrypt video", e);
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                notifyWaiting(key, null, e);
            } finally {
                if (streams != null) {
                    streams.close();
                }
            }
        }).start();
    }

    /**
     * Warm the cache for {@code galleryFile} without any callback. Used to decrypt the
     * next/previous clips ahead of a swipe.
     */
    public static void prefetch(@NonNull Context context, @NonNull GalleryFile galleryFile) {
        getDecryptedFile(context, galleryFile, null);
    }

    private static void evictOld(@NonNull String key) {
        synchronized (LRU) {
            LRU.remove(key);
            LRU.addLast(key);
            while (LRU.size() > MAX_CACHED) {
                String oldest = LRU.removeFirst();
                File old = READY_FILES.remove(oldest);
                if (old != null) {
                    // If mpv still has this file open it keeps playing; the inode is freed on close.
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
            }
        }
    }

    private static void notifyWaiting(@NonNull String key, @Nullable File file, @Nullable Exception error) {
        final List<Callback> callbacks;
        synchronized (IN_PROGRESS) {
            callbacks = IN_PROGRESS.remove(key);
        }
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        MAIN_HANDLER.post(() -> {
            for (Callback cb : callbacks) {
                if (file != null) {
                    cb.onReady(file);
                } else if (error != null) {
                    cb.onError(error);
                }
            }
        });
    }

    /**
     * Delete all decrypted plaintext videos. Call this when the video viewer is torn
     * down so plaintext never lingers on disk.
     */
    public static void clearAll(@Nullable Context context) {
        READY_FILES.clear();
        synchronized (IN_PROGRESS) {
            IN_PROGRESS.clear();
        }
        synchronized (LRU) {
            LRU.clear();
        }
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
