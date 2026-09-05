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

package se.arctosoft.vault.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import se.arctosoft.vault.data.GalleryFile;

/**
 * Holds the state of the vertical (TikTok style) video viewer. Because it is a
 * {@link ViewModel} it outlives configuration changes such as screen rotation, so the
 * playing video, its playback position and paused state are restored automatically
 * when the fragment is recreated in the new orientation.
 */
public class VideoViewerViewModel extends ViewModel {
    private final List<GalleryFile> videos = new ArrayList<>();
    private int currentIndex = 0;
    private long savedPositionMs = 0;
    private boolean paused = false;

    @NonNull
    public List<GalleryFile> getVideos() {
        return videos;
    }

    public void setVideos(@NonNull List<GalleryFile> newVideos, int startIndex) {
        videos.clear();
        videos.addAll(newVideos);
        currentIndex = Math.max(0, Math.min(startIndex, videos.size() - 1));
        savedPositionMs = 0;
        paused = false;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        if (this.currentIndex != currentIndex) {
            // Moving to another clip resets the resume position.
            savedPositionMs = 0;
        }
        this.currentIndex = currentIndex;
    }

    public long getSavedPositionMs() {
        return savedPositionMs;
    }

    public void setSavedPositionMs(long savedPositionMs) {
        this.savedPositionMs = savedPositionMs;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }
}
