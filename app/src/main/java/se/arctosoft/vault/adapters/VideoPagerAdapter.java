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

package se.arctosoft.vault.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

import se.arctosoft.vault.data.GalleryFile;
import se.arctosoft.vault.databinding.AdapterVideoViewerItemBinding;
import se.arctosoft.vault.utils.GlideStuff;

/**
 * Adapter backing the vertical (TikTok style) video pager. Each page owns a
 * {@link Surface} that the single shared mpv core renders into while the page is the
 * current one. The adapter does not drive playback itself; it forwards surface
 * lifecycle and tap events to {@link Listener} (the fragment), which owns the mpv core.
 */
public class VideoPagerAdapter extends RecyclerView.Adapter<VideoPagerAdapter.VideoViewHolder> {

    public interface Listener {
        void onSurfaceAvailable(int position, @NonNull Surface surface, int width, int height);

        void onSurfaceSizeChanged(int position, @NonNull Surface surface, int width, int height);

        void onSurfaceDestroyed(int position);

        void onItemTapped(int position);
    }

    private final Context context;
    private final List<GalleryFile> videos;
    private final boolean useDiskCache;
    private final Listener listener;
    private RecyclerView recyclerView;

    public VideoPagerAdapter(@NonNull Context context, @NonNull List<GalleryFile> videos, boolean useDiskCache, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.videos = videos;
        this.useDiskCache = useDiskCache;
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.recyclerView = null;
    }

    @Nullable
    private VideoViewHolder holderAt(int position) {
        if (recyclerView == null || position < 0) {
            return null;
        }
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(position);
        return vh instanceof VideoViewHolder ? (VideoViewHolder) vh : null;
    }

    /** Update the thin progress bar of the page at {@code position} and hide its thumbnail once playback has started. */
    public void updateProgress(int position, long positionMs, long durationMs) {
        VideoViewHolder holder = holderAt(position);
        if (holder != null) {
            holder.setProgress(positionMs, durationMs);
            if (positionMs > 0) {
                holder.showThumbnail(false);
            }
        }
    }

    public void showThumbnail(int position, boolean show) {
        VideoViewHolder holder = holderAt(position);
        if (holder != null) {
            holder.showThumbnail(show);
        }
    }

    public void setPausedIcon(int position, boolean paused) {
        VideoViewHolder holder = holderAt(position);
        if (holder != null) {
            holder.setPausedIcon(paused);
        }
    }

    @Nullable
    public Surface getSurfaceAt(int position) {
        VideoViewHolder holder = holderAt(position);
        return holder != null ? holder.getSurface() : null;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        AdapterVideoViewerItemBinding binding = AdapterVideoViewerItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VideoViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        GalleryFile galleryFile = videos.get(position);
        holder.binding.txtName.setText(galleryFile.getName());
        holder.binding.imgThumb.setVisibility(View.VISIBLE);
        holder.binding.progressLoading.setVisibility(View.VISIBLE);
        holder.binding.imgPlayPause.setAlpha(0f);
        holder.binding.progressBar.setProgress(0);
        Glide.with(context)
                .load(galleryFile.getThumbUri())
                .apply(GlideStuff.getRequestOptions(useDiskCache))
                .into(holder.binding.imgThumb);
        holder.binding.getRoot().setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onItemTapped(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    class VideoViewHolder extends RecyclerView.ViewHolder implements SurfaceHolder.Callback {
        final AdapterVideoViewerItemBinding binding;
        private Surface surface;

        VideoViewHolder(@NonNull AdapterVideoViewerItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.surfaceView.getHolder().addCallback(this);
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            // Surface itself is created in surfaceChanged where the size is known.
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            int pos = getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) {
                return;
            }
            Surface newSurface = holder.getSurface();
            if (newSurface != surface) {
                surface = newSurface;
                listener.onSurfaceAvailable(pos, newSurface, width, height);
            } else {
                listener.onSurfaceSizeChanged(pos, newSurface, width, height);
            }
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            int pos = getBindingAdapterPosition();
            surface = null;
            if (pos != RecyclerView.NO_POSITION) {
                listener.onSurfaceDestroyed(pos);
            } else {
                listener.onSurfaceDestroyed(-1);
            }
        }

        void showThumbnail(boolean show) {
            binding.imgThumb.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.progressLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        void setProgress(long positionMs, long durationMs) {
            if (durationMs > 0) {
                binding.progressBar.setProgress((int) (positionMs * 1000 / durationMs));
            }
        }

        void setPausedIcon(boolean paused) {
            binding.imgPlayPause.animate().alpha(paused ? 0.85f : 0f).setDuration(150).start();
        }

        @Nullable
        Surface getSurface() {
            return surface;
        }
    }
}
