package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays and rotates a theme's local wallpapers while Home is visible: images, looping muted videos, and
 * day/night variants of either (name.day.jpg / name.night.mp4 form one entry, keyed "name").
 * The video player exists only while Home is on screen, so the hardware decoder is free for other apps.
 */
final class WallpaperPlayer implements TextureView.SurfaceTextureListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener, MediaPlayer.OnVideoSizeChangedListener {
    private final FrameLayout root;
    private final ImageView image;
    /** Entry key to its files: 0 = day (or the only file), 1 = night. */
    private final Map<String, File[]> entries = new LinkedHashMap<>();
    private final long interval;
    private final double latitude;
    private final double longitude;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    private TextureView video;
    private Surface surface;
    private MediaPlayer media;
    private File videoFile;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        for (File file : config.wallpapers) {
            String key = WallpaperRotation.key(file.getName());
            File[] pair = entries.get(key);
            if (pair == null) entries.put(key, pair = new File[2]);
            pair[WallpaperRotation.nightVariant(file.getName()) ? 1 : 0] = file;
        }
        interval = config.wallpaperRotationMinutes * 60_000L;
        latitude = config.latitude;
        longitude = config.longitude;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (config.wallpaperEnabled && !entries.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
        }
    }

    /** Without coordinates the day is 07:00 to 19:00 local. */
    private boolean night() {
        ZonedDateTime now = ZonedDateTime.now();
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) return now.getHour() < 7 || now.getHour() >= 19;
        return Sky.isNight(latitude, longitude, now);
    }

    private File fileFor(String key) {
        File[] pair = entries.get(key);
        File first = night() ? pair[1] : pair[0];
        return first != null ? first : pair[0] != null ? pair[0] : pair[1];
    }

    private String pickKey(String previous) {
        return WallpaperRotation.pickKey(new ArrayList<>(entries.keySet()), previous, random);
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background. A video is only set up here (it plays from start()).
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        String key = entries.containsKey(previous) ? previous : pickKey(previous);
        while (key != null) {
            File file = fileFor(key);
            boolean shown;
            if (WallpaperRotation.video(file.getName())) {
                showVideo(file);
                shown = true;
            } else {
                Bitmap bitmap = decode(file);
                shown = bitmap != null;
                if (shown) image.setImageBitmap(bitmap);
            }
            if (shown) {
                displayed = file.getName();
                if (!key.equals(previous)) {
                    preferences.edit().putString("image", key)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            entries.remove(key);   // unreadable or over 4K: try another, or keep the plain colour
            key = pickKey(previous);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
        releaseMedia();
    }

    void close() {
        active = false;
        closed = true;
        releaseMedia();
        releaseSurface();
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || entries.isEmpty()) return;
        String previous = preferences.getString("image", "");
        String key = entries.containsKey(previous) ? previous : null;
        long changed = preferences.getLong("changed", 0);
        if (key == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            key = pickKey(previous);
        }
        if (key == null) return;
        File next = fileFor(key);
        if (next.getName().equals(displayed)) {
            playVideo();
            return;
        }
        String nextKey = key;
        if (WallpaperRotation.video(next.getName())) {
            showVideo(next);
            if (videoFile != next) return;   // it failed to start and update() already moved on
            displayed = next.getName();
            remember(nextKey, previous, changed);
            return;
        }
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    entries.remove(nextKey);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                image.setImageBitmap(bitmap);
                releaseMedia();
                videoFile = null;
                if (video != null) video.setAlpha(0f);
                displayed = next.getName();
                remember(nextKey, previous, changed);
            });
        });
    }

    /** A new key is stored with its time; the same key keeps its time, so a day/night switch never postpones the rotation. */
    private void remember(String key, String previous, long changed) {
        if (!key.equals(previous) || changed == 0) {
            preferences.edit().putString("image", key)
                    .putLong("changed", System.currentTimeMillis()).apply();
        }
    }

    // ---- video ---------------------------------------------------------------------------------

    private void showVideo(File file) {
        if (video == null) {
            video = new TextureView(root.getContext());
            video.setAlpha(0f);
            video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            video.setSurfaceTextureListener(this);
            root.addView(video, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        releaseMedia();
        videoFile = file;
        playVideo();
    }

    private void playVideo() {
        if (!active || closed || videoFile == null || surface == null || media != null) return;
        try {
            media = new MediaPlayer();
            media.setDataSource(videoFile.getPath());
            media.setSurface(surface);
            media.setLooping(true);
            media.setVolume(0f, 0f);
            media.setOnPreparedListener(this);
            media.setOnErrorListener(this);
            media.setOnInfoListener(this);
            media.setOnVideoSizeChangedListener(this);
            media.prepareAsync();
        } catch (Exception failure) {
            videoFailed();
        }
    }

    private void videoFailed() {
        releaseMedia();
        String name = videoFile == null ? "" : videoFile.getName();
        Log.w("TvHome.Wallpaper", "Skipping unplayable video: " + name);
        entries.remove(WallpaperRotation.key(name));
        videoFile = null;
        if (entries.isEmpty()) {
            image.setImageDrawable(null);
            video.setAlpha(0f);
        }
        update();
    }

    private void releaseMedia() {
        if (media != null) {
            media.release();
            media = null;
        }
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        player.start();
    }

    @Override
    public boolean onError(MediaPlayer player, int what, int extra) {
        videoFailed();
        return true;
    }

    @Override
    public boolean onInfo(MediaPlayer player, int what, int extra) {
        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
            video.setAlpha(1f);
            image.setImageDrawable(null);
        } else if (what == MediaPlayer.MEDIA_INFO_VIDEO_NOT_PLAYING) {
            videoFailed();   // no decoder for this video: the audio would play on, the picture never
        }
        return true;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer player, int w, int h) {
        if (w <= 0 || h <= 0) return;
        if ((long) w * h > 3840L * 2160) {
            videoFailed();
            return;
        }
        float viewW = video.getWidth(), viewH = video.getHeight();
        float s = Math.max(viewW / w, viewH / h);
        Matrix matrix = new Matrix();
        matrix.setScale(w * s / viewW, h * s / viewH, viewW / 2f, viewH / 2f);
        video.setTransform(matrix);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        releaseSurface();
        surface = new Surface(texture);
        playVideo();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releaseMedia();
        releaseSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {}

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
