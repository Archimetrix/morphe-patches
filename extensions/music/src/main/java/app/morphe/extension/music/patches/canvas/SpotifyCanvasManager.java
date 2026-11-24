package app.morphe.extension.music.patches.canvas;

import android.graphics.SurfaceTexture;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.PlaybackState;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.requests.SpotifyProvider;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Manages fetching, caching, and playing Spotify Canvas video loops
 * in the background of YouTube Music, hiding the static album art thumbnail
 * while a Canvas is active.
 */
public final class SpotifyCanvasManager {

    private static final SpotifyCanvasManager INSTANCE = new SpotifyCanvasManager();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, String> canvasCache = Collections.synchronizedMap(Utils.createSizeRestrictedMap(64));
    private final AtomicInteger currentRequestId = new AtomicInteger();

    @Nullable
    private volatile String currentTrackKey = null;
    @Nullable
    private volatile String currentCanvasUrl = null;

    private MediaPlayer mediaPlayer;
    private WeakReference<TextureView> canvasTextureRef = new WeakReference<>(null);
    private WeakReference<View> currentThumbnailRef = new WeakReference<>(null);
    private WeakReference<ViewGroup> currentContainerRef = new WeakReference<>(null);

    private boolean isPlaying = false;

    private SpotifyCanvasManager() {
    }

    public static SpotifyCanvasManager getInstance() {
        return INSTANCE;
    }

    /**
     * Called whenever a new song starts and metadata is dispatched.
     */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        if (!Settings.SPOTIFY_CANVAS_ENABLED.get() || metadata == null) {
            stopCanvas();
            return;
        }

        final String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (rawTitle == null || rawTitle.trim().isEmpty()) {
            stopCanvas();
            return;
        }

        final String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(rawTitle, rawArtist);
        final String effectiveTitle = parsed[1];
        final String effectiveArtist = parsed[0];
        final String trackKey = effectiveTitle + " // " + effectiveArtist;

        if (Objects.equals(trackKey, currentTrackKey)) {
            return;
        }

        currentTrackKey = trackKey;
        final int requestId = currentRequestId.incrementAndGet();

        // 1. Check local memory cache
        String cachedUrl = canvasCache.get(trackKey);
        if (cachedUrl != null) {
            handleCanvasResolved(requestId, cachedUrl);
            return;
        }

        // 2. Fetch asynchronously from Spotify
        executor.execute(() -> {
            try {
                String canvasUrl = SpotifyProvider.fetchCanvasForTrack(effectiveTitle, effectiveArtist);
                if (requestId == currentRequestId.get()) {
                    if (canvasUrl != null && !canvasUrl.isEmpty()) {
                        canvasCache.put(trackKey, canvasUrl);
                    }
                    handleCanvasResolved(requestId, canvasUrl);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Error fetching canvas for: " + trackKey, ex);
                if (requestId == currentRequestId.get()) {
                    handleCanvasResolved(requestId, null);
                }
            }
        });
    }

    private void handleCanvasResolved(int requestId, @Nullable String canvasUrl) {
        Utils.runOnMainThread(() -> {
            if (requestId != currentRequestId.get()) {
                return;
            }
            if (canvasUrl == null || canvasUrl.isEmpty()) {
                stopCanvas();
            } else {
                currentCanvasUrl = canvasUrl;
                startCanvasPlayback(canvasUrl);
            }
        });
    }

    /**
     * Binds the player container and thumbnail view for Canvas overlay.
     */
    public void bindViews(@NonNull ViewGroup container, @Nullable View thumbnailView) {
        currentContainerRef = new WeakReference<>(container);
        if (thumbnailView != null) {
            currentThumbnailRef = new WeakReference<>(thumbnailView);
        }
        ensureCanvasView(container);
    }

    private TextureView ensureCanvasView(ViewGroup container) {
        TextureView textureView = canvasTextureRef.get();
        if (textureView == null || textureView.getParent() != container) {
            if (textureView != null && textureView.getParent() instanceof ViewGroup) {
                ((ViewGroup) textureView.getParent()).removeView(textureView);
            }
            textureView = new TextureView(container.getContext());
            textureView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            textureView.setAlpha(0f);
            // Insert behind controls or over artwork
            container.addView(textureView, 0);
            canvasTextureRef = new WeakReference<>(textureView);

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    if (currentCanvasUrl != null) {
                        startCanvasPlayback(currentCanvasUrl);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    releaseMediaPlayer();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });
        }
        return textureView;
    }

    private void startCanvasPlayback(@NonNull String videoUrl) {
        ViewGroup container = currentContainerRef.get();
        if (container == null) {
            // Find watch_fragment_container if not explicitly bound
            findAndBindViews();
            container = currentContainerRef.get();
            if (container == null) return;
        }

        TextureView canvasView = ensureCanvasView(container);
        SurfaceTexture surfaceTexture = canvasView.getSurfaceTexture();
        if (surfaceTexture == null) {
            return;
        }

        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            } else {
                mediaPlayer.reset();
            }

            mediaPlayer.setDataSource(videoUrl);
            mediaPlayer.setSurface(new Surface(surfaceTexture));
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f); // Canvas is video-only loop (muted)

            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPlaying = true;
                // Reveal canvas smoothly
                canvasView.animate().alpha(1f).setDuration(400).start();
                // Hide native thumbnail
                View thumbnail = currentThumbnailRef.get();
                if (thumbnail != null) {
                    thumbnail.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                        thumbnail.setVisibility(View.INVISIBLE);
                    }).start();
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Logger.printDebug(() -> "Canvas MediaPlayer error: " + what + ", " + extra);
                stopCanvas();
                return true;
            });

            mediaPlayer.prepareAsync();
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to start canvas playback", ex);
            stopCanvas();
        }
    }

    public void stopCanvas() {
        currentCanvasUrl = null;
        Utils.runOnMainThread(() -> {
            releaseMediaPlayer();
            TextureView canvasView = canvasTextureRef.get();
            if (canvasView != null) {
                canvasView.animate().alpha(0f).setDuration(250).start();
            }
            View thumbnail = currentThumbnailRef.get();
            if (thumbnail != null) {
                thumbnail.setVisibility(View.VISIBLE);
                thumbnail.animate().alpha(1f).setDuration(250).start();
            }
        });
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    /**
     * Synchronizes canvas playback with user play/pause state.
     */
    public void onSetPlaybackState(@Nullable PlaybackState playbackState) {
        if (playbackState == null || mediaPlayer == null) return;
        int state = playbackState.getState();
        Utils.runOnMainThread(() -> {
            if (mediaPlayer == null) return;
            try {
                if (state == PlaybackState.STATE_PLAYING && !isPlaying) {
                    mediaPlayer.start();
                    isPlaying = true;
                } else if (state == PlaybackState.STATE_PAUSED && isPlaying) {
                    mediaPlayer.pause();
                    isPlaying = false;
                }
            } catch (Exception ignored) {}
        });
    }

    private void findAndBindViews() {
        try {
            int containerId = ResourceUtils.getIdentifier(ResourceType.ID, "watch_fragment_container");
            if (containerId == 0) return;
            // Scan through any known view context
            // When views attach, they register with bindViews
        } catch (Exception ignored) {}
    }
}
