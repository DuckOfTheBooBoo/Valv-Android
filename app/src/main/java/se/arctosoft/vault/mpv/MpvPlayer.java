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
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.jdtech.mpv.MPVLib;

/**
 * Thin wrapper around the single, global libmpv core exposed by {@link MPVLib}.
 * <p>
 * libmpv keeps a single native player instance, so only one {@link MpvPlayer} may
 * be initialised at a time. The vertical video viewer owns the instance while it is
 * visible and releases it when it goes away. Playback is driven purely through the
 * string based command/property API which is stable across mpv-android forks.
 */
public class MpvPlayer {
    private static final String TAG = "MpvPlayer";

    private boolean initialised = false;
    private boolean hasSurface = false;

    public MpvPlayer(@NonNull Context context) {
        try {
            MPVLib.create(context.getApplicationContext());
            initOptions();
            MPVLib.init();
            initialised = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to create mpv core", t);
            initialised = false;
        }
    }

    private void initOptions() {
        // Rendering
        MPVLib.setOptionString("config", "no");
        MPVLib.setOptionString("vo", "gpu");
        MPVLib.setOptionString("gpu-context", "android");
        MPVLib.setOptionString("opengl-es", "yes");
        MPVLib.setOptionString("force-window", "no");
        // Decoding
        MPVLib.setOptionString("hwdec", "auto");
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
        // Audio
        MPVLib.setOptionString("ao", "audiotrack,opensles");
        // Behaviour: loop the current clip (TikTok style) and do not persist positions to disk
        MPVLib.setOptionString("loop-file", "inf");
        MPVLib.setOptionString("save-position-on-quit", "no");
        MPVLib.setOptionString("keep-open", "yes");
        MPVLib.setOptionString("idle", "yes");
    }

    public boolean isInitialised() {
        return initialised;
    }

    public void attachSurface(@NonNull Surface surface, int width, int height) {
        if (!initialised) {
            return;
        }
        MPVLib.attachSurface(surface);
        MPVLib.setOptionString("android-surface-size", width + "x" + height);
        MPVLib.setPropertyString("vo", "gpu");
        hasSurface = true;
    }

    public void updateSurfaceSize(int width, int height) {
        if (!initialised || !hasSurface) {
            return;
        }
        MPVLib.setOptionString("android-surface-size", width + "x" + height);
    }

    public void detachSurface() {
        if (!initialised || !hasSurface) {
            return;
        }
        MPVLib.setPropertyString("vo", "null");
        MPVLib.setOptionString("force-window", "no");
        MPVLib.detachSurface();
        hasSurface = false;
    }

    /**
     * Load and start playing the given local file.
     *
     * @param filePath absolute path to a (decrypted) media file
     * @param startMs  position to resume from, in milliseconds
     * @param paused   whether playback should start paused
     */
    public void playFile(@NonNull String filePath, long startMs, boolean paused) {
        if (!initialised) {
            return;
        }
        double startSeconds = Math.max(0, startMs) / 1000d;
        MPVLib.setOptionString("start", String.valueOf(startSeconds));
        MPVLib.setPropertyString("pause", paused ? "yes" : "no");
        MPVLib.command(new String[]{"loadfile", filePath});
    }

    public void stop() {
        if (!initialised) {
            return;
        }
        MPVLib.command(new String[]{"stop"});
    }

    public void setPaused(boolean paused) {
        if (!initialised) {
            return;
        }
        MPVLib.setPropertyString("pause", paused ? "yes" : "no");
    }

    public boolean isPaused() {
        if (!initialised) {
            return true;
        }
        String value = MPVLib.getPropertyString("pause");
        return value == null || value.equals("yes") || value.equals("true");
    }

    public void togglePause() {
        setPaused(!isPaused());
    }

    public void seekTo(long positionMs) {
        if (!initialised) {
            return;
        }
        double seconds = Math.max(0, positionMs) / 1000d;
        MPVLib.command(new String[]{"seek", String.valueOf(seconds), "absolute"});
    }

    public long getPositionMs() {
        return parseSecondsToMs(MPVLib.getPropertyString("time-pos"));
    }

    public long getDurationMs() {
        return parseSecondsToMs(MPVLib.getPropertyString("duration"));
    }

    private long parseSecondsToMs(@Nullable String value) {
        if (!initialised || value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return (long) (Double.parseDouble(value) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void destroy() {
        if (!initialised) {
            return;
        }
        try {
            detachSurface();
            MPVLib.command(new String[]{"stop"});
            MPVLib.destroy();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to destroy mpv core", t);
        } finally {
            initialised = false;
        }
    }
}
