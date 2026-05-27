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

package se.arctosoft.vault.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.jdtech.mpv.MPVLib;

public class MpvPlayer implements TextureView.SurfaceTextureListener, MPVLib.EventObserver {
    public interface Listener {
        void onPlayingChanged(boolean isPlaying);

        void onPlaybackEnded();

        void onPlaybackError(@NonNull String message);
    }

    private final Context context;
    private final TextureView textureView;
    private MPVLib mpv;
    private Surface surface;
    private Listener listener;
    private boolean isPlaying;
    private boolean endNotified;
    private String pendingSource;
    private boolean pendingAutoplay;

    public MpvPlayer(@NonNull Context context, @NonNull TextureView textureView) {
        this.context = context.getApplicationContext();
        this.textureView = textureView;
        this.textureView.setSurfaceTextureListener(this);
        if (textureView.isAvailable()) {
            onSurfaceTextureAvailable(textureView.getSurfaceTexture(), textureView.getWidth(), textureView.getHeight());
        }
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void play(@NonNull String source, boolean autoplay) {
        endNotified = false;
        pendingSource = source;
        pendingAutoplay = autoplay;
        if (mpv != null) {
            loadPendingSource();
        }
    }

    public void pause() {
        if (mpv != null) {
            mpv.setPropertyBoolean("pause", true);
        }
    }

    public void release() {
        textureView.setSurfaceTextureListener(null);
        if (mpv != null) {
            mpv.removeObserver(this);
            mpv.detachSurface();
            mpv.destroy();
            mpv = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        isPlaying = false;
        endNotified = false;
        pendingSource = null;
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        surface = new Surface(surfaceTexture);
        if (mpv == null) {
            mpv = MPVLib.create(context);
            if (mpv == null) {
                notifyError("Failed to initialize MPV");
                return;
            }
            configureMpv(mpv);
            mpv.init();
            mpv.addObserver(this);
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        }
        mpv.attachSurface(surface);
        mpv.setPropertyString("android-surface-size", width + "x" + height);
        loadPendingSource();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        if (mpv != null) {
            mpv.setPropertyString("android-surface-size", width + "x" + height);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        if (mpv != null) {
            mpv.detachSurface();
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
    }

    private void configureMpv(@NonNull MPVLib mpvLib) {
        mpvLib.setOptionString("vo", "gpu");
        mpvLib.setOptionString("gpu-context", "android");
        mpvLib.setOptionString("opengl-es", "yes");
        mpvLib.setOptionString("hwdec", "mediacodec");
        mpvLib.setOptionString("ao", "opensles");
        mpvLib.setOptionString("keep-open", "no");
        mpvLib.setOptionString("force-window", "yes");
        mpvLib.setOptionString("ytdl", "no");
    }

    private void loadPendingSource() {
        if (mpv == null || pendingSource == null) {
            return;
        }
        String source = pendingSource;
        boolean autoplay = pendingAutoplay;
        pendingSource = null;
        try {
            mpv.command(new String[]{"loadfile", source});
            mpv.setPropertyBoolean("pause", !autoplay);
            updatePlayingState(autoplay);
        } catch (RuntimeException e) {
            notifyError(e.getMessage());
        }
    }

    private void updatePlayingState(boolean playing) {
        if (isPlaying != playing) {
            isPlaying = playing;
            if (listener != null) {
                listener.onPlayingChanged(playing);
            }
        }
    }

    private void notifyPlaybackEnded() {
        if (!endNotified) {
            endNotified = true;
            if (listener != null) {
                listener.onPlaybackEnded();
            }
        }
    }

    private void notifyError(@Nullable String message) {
        if (listener != null) {
            listener.onPlaybackError(message == null ? "Unknown error" : message);
        }
    }

    @Override
    public void eventProperty(@NonNull String property) {
    }

    @Override
    public void eventProperty(@NonNull String property, long value) {
    }

    @Override
    public void eventProperty(@NonNull String property, double value) {
    }

    @Override
    public void eventProperty(@NonNull String property, boolean value) {
        if ("pause".equals(property)) {
            updatePlayingState(!value);
        } else if ("eof-reached".equals(property) && value) {
            notifyPlaybackEnded();
        }
    }

    @Override
    public void eventProperty(@NonNull String property, @NonNull String value) {
    }

    @Override
    public void event(int eventId) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            notifyPlaybackEnded();
        }
    }
}
