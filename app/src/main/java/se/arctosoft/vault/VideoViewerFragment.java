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

package se.arctosoft.vault;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.util.List;

import se.arctosoft.vault.adapters.VideoPagerAdapter;
import se.arctosoft.vault.data.GalleryFile;
import se.arctosoft.vault.databinding.FragmentVideoViewerBinding;
import se.arctosoft.vault.mpv.MpvPlayer;
import se.arctosoft.vault.mpv.VideoCache;
import se.arctosoft.vault.utils.Settings;
import se.arctosoft.vault.utils.Toaster;
import se.arctosoft.vault.viewmodel.VideoViewerViewModel;

/**
 * Full screen, vertically scrolling video viewer inspired by TikTok. Swiping up/down
 * moves to the previous/next video, the current clip loops, and a single shared mpv
 * core (see {@link MpvPlayer}) renders into whichever page is currently selected.
 * <p>
 * Playback state (which clip, position and paused flag) lives in
 * {@link VideoViewerViewModel}, so it is preserved across configuration changes such as
 * screen rotation.
 */
public class VideoViewerFragment extends Fragment implements VideoPagerAdapter.Listener {
    private static final long POLL_INTERVAL_MS = 250;

    private FragmentVideoViewerBinding binding;
    private VideoViewerViewModel viewModel;
    private VideoPagerAdapter adapter;
    private MpvPlayer mpvPlayer;
    private List<GalleryFile> videos;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentPosition = RecyclerView.NO_POSITION;
    private int loadedPosition = RecyclerView.NO_POSITION;
    private int decryptingPosition = RecyclerView.NO_POSITION;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (mpvPlayer != null && loadedPosition == currentPosition && currentPosition != RecyclerView.NO_POSITION) {
                long position = mpvPlayer.getPositionMs();
                long duration = mpvPlayer.getDurationMs();
                adapter.updateProgress(currentPosition, position, duration);
                if (position > 0) {
                    viewModel.setSavedPositionMs(position);
                }
                viewModel.setPaused(mpvPlayer.isPaused());
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentVideoViewerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(VideoViewerViewModel.class);
        videos = viewModel.getVideos();

        if (videos.isEmpty()) {
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }

        setPadding();
        binding.btnClose.setOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());

        mpvPlayer = new MpvPlayer(requireContext());
        if (!mpvPlayer.isInitialised()) {
            Toaster.getInstance(requireContext()).showLong(getString(R.string.gallery_video_error, "mpv"));
        }

        adapter = new VideoPagerAdapter(requireContext(), videos, Settings.getInstance(requireContext()).useDiskCache(), this);
        binding.videoPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        binding.videoPager.setAdapter(adapter);
        binding.videoPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                selectPage(position);
            }
        });

        int startIndex = viewModel.getCurrentIndex();
        binding.videoPager.setCurrentItem(startIndex, false);
        binding.videoPager.post(() -> selectPage(startIndex));
    }

    private void setPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.btnClose, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            params.leftMargin = bars.left;
            params.topMargin = bars.top;
            v.setLayoutParams(params);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void selectPage(int position) {
        if (position == currentPosition || position < 0 || position >= videos.size()) {
            return;
        }
        currentPosition = position;
        loadedPosition = RecyclerView.NO_POSITION;
        viewModel.setCurrentIndex(position);
        startPlaybackForCurrent();
    }

    private void startPlaybackForCurrent() {
        if (mpvPlayer == null || currentPosition == RecyclerView.NO_POSITION) {
            return;
        }
        if (loadedPosition == currentPosition || decryptingPosition == currentPosition) {
            return;
        }
        final int loadingPosition = currentPosition;
        decryptingPosition = loadingPosition;
        adapter.setPausedIcon(currentPosition, false);
        VideoCache.getDecryptedFile(requireContext(), videos.get(loadingPosition), new VideoCache.Callback() {
            @Override
            public void onReady(@NonNull File file) {
                if (decryptingPosition == loadingPosition) {
                    decryptingPosition = RecyclerView.NO_POSITION;
                }
                if (loadingPosition != currentPosition || mpvPlayer == null) {
                    return;
                }
                attachCurrentSurface();
                mpvPlayer.playFile(file.getAbsolutePath(), viewModel.getSavedPositionMs(), viewModel.isPaused());
                loadedPosition = loadingPosition;
                adapter.setPausedIcon(currentPosition, viewModel.isPaused());
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (decryptingPosition == loadingPosition) {
                    decryptingPosition = RecyclerView.NO_POSITION;
                }
                if (isAdded()) {
                    Toaster.getInstance(requireContext()).showLong(getString(R.string.gallery_video_error, e.getMessage()));
                }
            }
        });
    }

    private void attachCurrentSurface() {
        if (mpvPlayer == null || currentPosition == RecyclerView.NO_POSITION) {
            return;
        }
        Surface surface = adapter.getSurfaceAt(currentPosition);
        if (surface != null && surface.isValid()) {
            View pageView = binding.videoPager.getChildAt(0);
            int width = pageView != null ? pageView.getWidth() : binding.videoPager.getWidth();
            int height = pageView != null ? pageView.getHeight() : binding.videoPager.getHeight();
            mpvPlayer.detachSurface();
            mpvPlayer.attachSurface(surface, Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void onSurfaceAvailable(int position, @NonNull Surface surface, int width, int height) {
        if (mpvPlayer == null || position != currentPosition) {
            return;
        }
        mpvPlayer.detachSurface();
        mpvPlayer.attachSurface(surface, Math.max(1, width), Math.max(1, height));
        if (loadedPosition != currentPosition) {
            startPlaybackForCurrent();
        }
    }

    @Override
    public void onSurfaceSizeChanged(int position, @NonNull Surface surface, int width, int height) {
        if (mpvPlayer != null && position == currentPosition) {
            mpvPlayer.updateSurfaceSize(Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void onSurfaceDestroyed(int position) {
        if (mpvPlayer != null && (position == currentPosition || position == -1)) {
            mpvPlayer.detachSurface();
        }
    }

    @Override
    public void onItemTapped(int position) {
        if (mpvPlayer == null || position != currentPosition || loadedPosition != currentPosition) {
            return;
        }
        mpvPlayer.togglePause();
        boolean paused = mpvPlayer.isPaused();
        viewModel.setPaused(paused);
        adapter.setPausedIcon(currentPosition, paused);
    }

    @Override
    public void onResume() {
        super.onResume();
        ActionBar ab = ((AppCompatActivity) requireActivity()).getSupportActionBar();
        if (ab != null) {
            ab.hide();
        }
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(poller);
        handler.postDelayed(poller, POLL_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(poller);
        if (mpvPlayer != null && loadedPosition == currentPosition) {
            long position = mpvPlayer.getPositionMs();
            if (position > 0) {
                viewModel.setSavedPositionMs(position);
            }
            if (!requireActivity().isChangingConfigurations()) {
                mpvPlayer.setPaused(true);
                viewModel.setPaused(true);
            }
        }
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ActionBar ab = ((AppCompatActivity) requireActivity()).getSupportActionBar();
        if (ab != null) {
            ab.show();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(poller);
        boolean changingConfigurations = getActivity() != null && requireActivity().isChangingConfigurations();
        if (mpvPlayer != null) {
            if (loadedPosition == currentPosition) {
                long position = mpvPlayer.getPositionMs();
                if (position > 0) {
                    viewModel.setSavedPositionMs(position);
                }
            }
            mpvPlayer.destroy();
            mpvPlayer = null;
        }
        if (!changingConfigurations) {
            VideoCache.clearAll(getContext());
        }
        currentPosition = RecyclerView.NO_POSITION;
        loadedPosition = RecyclerView.NO_POSITION;
        decryptingPosition = RecyclerView.NO_POSITION;
        binding = null;
        super.onDestroyView();
    }
}
