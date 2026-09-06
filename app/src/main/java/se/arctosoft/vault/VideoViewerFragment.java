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
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupMenu;

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
import se.arctosoft.vault.utils.Dialogs;
import se.arctosoft.vault.utils.Settings;
import se.arctosoft.vault.utils.TagStore;
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

    private static final long SPEED_HOLD_DELAY_MS = 300;
    private float downX, downY;
    private int touchSlop;
    private boolean speedHeld = false;
    private boolean suppressNextTap = false;
    private final Runnable speedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mpvPlayer != null && loadedPosition == currentPosition) {
                mpvPlayer.setSpeed(2.0);
                speedHeld = true;
                adapter.setSpeedIndicator(currentPosition, true);
            }
        }
    };

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

        touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        binding.btnClose.setOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.btnMenu.setOnClickListener(this::showMenu);

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
        setupSpeedHold();
        applyInsets();

        int startIndex = viewModel.getCurrentIndex();
        binding.videoPager.setCurrentItem(startIndex, false);
        binding.videoPager.post(() -> selectPage(startIndex));
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int base = Math.round(8 * getResources().getDisplayMetrics().density);
            setMargins(binding.btnClose, bars.left + base, bars.top + base, base);
            setMargins(binding.btnMenu, base, bars.top + base, bars.right + base);
            if (adapter != null) {
                adapter.setBottomInset(bars.bottom);
            }
            return insets;
        });
        binding.getRoot().requestApplyInsets();
    }

    private void setMargins(@NonNull View v, int left, int top, int right) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        v.setLayoutParams(params);
    }

    private void showMenu(@NonNull View anchor) {
        if (currentPosition == RecyclerView.NO_POSITION) {
            return;
        }
        GalleryFile galleryFile = videos.get(currentPosition);
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, R.id.edit_tags, 0, R.string.edit_tags);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.edit_tags) {
                String current = TagStore.getTag(requireContext(), galleryFile);
                Dialogs.showEditTagDialog(requireActivity(), current, text -> {
                    TagStore.setTag(requireContext(), galleryFile, text);
                    adapter.refreshTag(currentPosition);
                });
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void setupSpeedHold() {
        View child = binding.videoPager.getChildAt(0);
        if (!(child instanceof RecyclerView)) {
            return;
        }
        RecyclerView rv = (RecyclerView) child;
        rv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
                handleSpeedTouch(recyclerView, e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        });
    }

    private void handleSpeedTouch(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                speedHeld = false;
                suppressNextTap = false;
                // Only the left/right thirds trigger 2x; the centre is reserved for tap-to-pause.
                boolean side = downX < rv.getWidth() * 0.4f || downX > rv.getWidth() * 0.6f;
                if (side && loadedPosition == currentPosition) {
                    handler.postDelayed(speedRunnable, SPEED_HOLD_DELAY_MS);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > touchSlop || Math.abs(e.getY() - downY) > touchSlop) {
                    cancelSpeedHold();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelSpeedHold();
                break;
        }
    }

    private void cancelSpeedHold() {
        handler.removeCallbacks(speedRunnable);
        if (speedHeld) {
            speedHeld = false;
            suppressNextTap = true;
            if (mpvPlayer != null) {
                mpvPlayer.setSpeed(1.0);
            }
            adapter.setSpeedIndicator(currentPosition, false);
        }
    }

    private void selectPage(int position) {
        if (position == currentPosition || position < 0 || position >= videos.size()) {
            return;
        }
        cancelSpeedHold();
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
        if (suppressNextTap) {
            suppressNextTap = false;
            return;
        }
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
        cancelSpeedHold();
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
