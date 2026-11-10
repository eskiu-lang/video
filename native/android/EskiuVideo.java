// <video> on Android: each player is a MediaPlayer (which plays the sound) with its state on
// the "video" channel (src/video.esk). VideoView is a TextureView the player draws into.
// With use_video_frames on, the player draws into a YUV_420_888 ImageReader instead, whose
// latest Image is lent to Eskiu (video.c reads its planes' direct buffers by address).
// Eskiu calls these methods on the UI thread, where the players live.
package org.eskiu.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaFormat;
import android.media.MediaExtractor;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import java.nio.ByteBuffer;
import java.util.HashMap;
import org.eskiu.ui.UiActivity;

public final class EskiuVideo {
    static final HashMap<Integer, Player> players = new HashMap<>();
    static int nextId = 1;
    static final Handler main = new Handler(Looper.getMainLooper());
    // Guards a view's surface while the video thread draws a frame into it (the main thread
    // releases and replaces it).
    static final Object viewLock = new Object();

    // A YUV_420_888 picture to RGBA bytes (video.c registers it).
    static native void yuvToRgba(ByteBuffer y, ByteBuffer u, ByteBuffer v, int[] layout, ByteBuffer out);

    public static void setup() {
        UiActivity.registerView("video", new UiActivity.ViewFactory() {
            @Override public View create(Context context, UiActivity.ViewEvents events) { return new Screen(context); }
            @Override public void set(View view, String name, String value) {
                Screen screen = (Screen)view;
                if (name.equals("player")) {
                    int id = 0;
                    try { id = Integer.parseInt(value); } catch (NumberFormatException error) { /* none */ }
                    screen.show(id);
                } else if (name.equals("fit")) {
                    screen.fit = value;
                    screen.layoutPicture();
                }
            }
        });
    }

    static void emit(int id, String what) { UiActivity.emit("video", id + " " + what); }

    // One video: the MediaPlayer, what Eskiu asked for, and the frames' reader.
    static final class Player {
        final int id;
        final MediaPlayer media = new MediaPlayer();
        boolean prepared, wantsPlay, loop, muted, ended, closed;
        float volume = 1, rate = 1;
        int width, height, rotation, matrix;
        boolean fullRange;
        Surface viewSurface;
        Screen screen;
        // Frames: on with the asked size; the reader and its thread; the newest image, the
        // one lent (they may be the same) and whether the id was posted since the last
        // acquire, guarded by `lock`.
        boolean framesOn;
        ImageReader reader;
        HandlerThread thread;
        final Object lock = new Object();
        Image latest, lent;
        boolean posted;
        // While frames are on the MediaPlayer draws into the reader, so each frame is drawn
        // into the VideoView from here: converted into `rgba`, copied to `picture`, drawn on
        // the view's surface with a canvas (the view then replaces its surface before a
        // MediaPlayer draws into it again).
        ByteBuffer rgba;
        Bitmap picture;
        final int[] layout = new int[13];
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Player(int id) { this.id = id; }

        void open(String source) {
            media.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            media.setOnPreparedListener(mp -> prepared());
            media.setOnCompletionListener(mp -> { if (!loop) { ended = true; wantsPlay = false; emit(id, "ended"); } });
            media.setOnErrorListener((mp, what, extra) -> { emit(id, "failed the player stopped (" + what + ", " + extra + ")"); return true; });
            media.setOnVideoSizeChangedListener((mp, w, h) -> { if (w > 0 && h > 0) { width = w; height = h; route(); if (screen != null) screen.layoutPicture(); } });
            // The rotation and the color description come from the container, read on a
            // thread of its own (it may be a download); then the player prepares.
            new Thread(() -> {
                describe(source);
                main.post(() -> {
                    if (closed) return;
                    try {
                        Context context = UiActivity.current();
                        if (source.startsWith("content://") && context != null) media.setDataSource(context, Uri.parse(source));
                        else media.setDataSource(source);
                        media.prepareAsync();
                    } catch (Exception error) {
                        emit(id, "failed " + (error.getMessage() == null ? "the video could not be opened" : error.getMessage()));
                    }
                });
            }).start();
        }

        // The video track's rotation, matrix and range, when the container says them.
        void describe(String source) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                Context context = UiActivity.current();
                if (source.startsWith("content://") && context != null) extractor.setDataSource(context, Uri.parse(source), null);
                else extractor.setDataSource(source);
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime == null || !mime.startsWith("video/")) continue;
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) rotation = ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360;
                    int h = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
                    matrix = h >= 720 ? 1 : 0;
                    if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                        matrix = format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709 ? 1 : 0;
                    }
                    if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                        fullRange = format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL;
                    }
                    break;
                }
            } catch (Exception error) {
                // The player reports what it cannot open.
            } finally {
                extractor.release();
            }
        }

        void prepared() {
            if (closed) return;
            prepared = true;
            width = media.getVideoWidth();
            height = media.getVideoHeight();
            apply();
            route();
            int duration = media.getDuration();
            boolean turned = rotation == 90 || rotation == 270;
            emit(id, "ready " + Math.max(0, duration) + " " + (turned ? height : width) + " " + (turned ? width : height));
            if (screen != null) screen.layoutPicture();
            if (wantsPlay) play();
        }

        void apply() {
            media.setLooping(loop);
            float v = muted ? 0f : Math.max(0f, Math.min(1f, volume));
            media.setVolume(v, v);
        }

        void play() {
            wantsPlay = true;
            if (!prepared) return;
            ended = false;
            media.start();
            // Setting the speed starts playback, so it is set only while playing.
            if (rate > 0 && rate != 1) {
                try { media.setPlaybackParams(new PlaybackParams().setSpeed(rate)); }
                catch (IllegalArgumentException | IllegalStateException error) { /* the device refuses that speed */ }
            }
            emit(id, "playing");
        }

        void pause() {
            wantsPlay = false;
            if (!prepared || !media.isPlaying()) return;
            media.pause();
            emit(id, "paused");
        }

        // The picture goes to the frames' reader while frames are on, else to the view.
        void route() {
            if (closed) return;
            if (framesOn && prepared && width > 0 && height > 0 &&
                (reader == null || reader.getWidth() != width || reader.getHeight() != height)) {
                closeReader();
                if (thread == null) { thread = new HandlerThread("video"); thread.start(); }
                // One waiting, one lent, and two for acquireLatestImage to step through.
                reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4);
                reader.setOnImageAvailableListener(this::frameReady, new Handler(thread.getLooper()));
            }
            if (!framesOn) closeReader();
            if (!framesOn && screen != null && screen.canvasUsed) screen.renewSurface();
            try { media.setSurface(framesOn && reader != null ? reader.getSurface() : viewSurface); }
            catch (IllegalStateException error) { /* released */ }
        }

        void frameReady(ImageReader from) {
            Image image;
            try { image = from.acquireLatestImage(); }
            catch (IllegalStateException error) { return; }
            if (image == null) return;
            drawIntoView(image);
            boolean post;
            synchronized (lock) {
                if (reader != from) { image.close(); return; }
                if (latest != null && latest != lent) latest.close();
                latest = image;
                post = !posted;
                posted = true;
            }
            if (post) UiActivity.emit("video_frame", Integer.toString(id));
        }

        // Draws a frame into the VideoView showing this player, upright and stretched to its
        // surface (the view's transform gives the picture its aspect ratio), on the video
        // thread.
        void drawIntoView(Image image) {
            synchronized (viewLock) {
                Surface surface = viewSurface;
                Screen view = screen;
                if (surface == null || view == null || !surface.isValid()) return;
                Image.Plane[] planes = image.getPlanes();
                if (planes.length != 3) return;
                Rect crop = image.getCropRect();
                int left = crop == null ? 0 : crop.left & ~1, top = crop == null ? 0 : crop.top & ~1;
                int w = crop == null ? image.getWidth() : crop.right - left;
                int h = crop == null ? image.getHeight() : crop.bottom - top;
                if (w <= 0 || h <= 0) return;
                ByteBuffer[] buffers = new ByteBuffer[3];
                for (int p = 0; p < 3; p++) {
                    buffers[p] = planes[p].getBuffer();
                    int row = planes[p].getRowStride(), pixel = planes[p].getPixelStride();
                    layout[p * 3] = buffers[p].position() + (p == 0 ? top * row + left * pixel : (top / 2) * row + (left / 2) * pixel);
                    layout[p * 3 + 1] = row;
                    layout[p * 3 + 2] = pixel;
                }
                layout[9] = w;
                layout[10] = h;
                layout[11] = matrix;
                layout[12] = fullRange ? 1 : 0;
                if (picture == null || picture.getWidth() != w || picture.getHeight() != h) {
                    picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    rgba = ByteBuffer.allocateDirect(w * h * 4);
                }
                yuvToRgba(buffers[0], buffers[1], buffers[2], layout, rgba);
                rgba.rewind();
                picture.copyPixelsFromBuffer(rgba);
                rgba.rewind();
                Canvas canvas;
                try {
                    canvas = Build.VERSION.SDK_INT >= 23 ? surface.lockHardwareCanvas() : surface.lockCanvas(null);
                } catch (IllegalArgumentException | IllegalStateException error) {
                    return;
                }
                boolean turned = rotation == 90 || rotation == 270;
                float uw = turned ? h : w, uh = turned ? w : h;
                Matrix m = new Matrix();
                m.postTranslate(-w / 2f, -h / 2f);
                m.postRotate(rotation);
                m.postScale(canvas.getWidth() / uw, canvas.getHeight() / uh);
                m.postTranslate(canvas.getWidth() / 2f, canvas.getHeight() / 2f);
                canvas.drawBitmap(picture, m, paint);
                surface.unlockCanvasAndPost(canvas);
                view.canvasUsed = true;
            }
        }

        void closeReader() {
            if (reader == null) return;
            ImageReader old = reader;
            synchronized (lock) {
                reader = null;
                if (latest != null && latest != lent) latest.close();
                latest = null;
                posted = false;
            }
            old.close();
        }

        void close() {
            closed = true;
            framesOn = false;
            frameRelease();
            closeReader();
            if (thread != null) { thread.quitSafely(); thread = null; }
            media.release();
        }

        // Lends the newest frame: `info` gets video_frame_read's layout (src/video.esk) but
        // the plane addresses, which video.c reads from the returned buffers, and each
        // plane's offset into its buffer (for the visible crop) in the address slots.
        ByteBuffer[] frameAcquire(long[] info) {
            frameRelease();
            Image image;
            synchronized (lock) {
                image = latest;
                posted = false;
                if (image == null) return null;
                lent = image;
            }
            try {
                Image.Plane[] planes = image.getPlanes();
                if (planes.length != 3) { frameRelease(); return null; }
                Rect crop = image.getCropRect();
                int left = crop == null ? 0 : crop.left & ~1, top = crop == null ? 0 : crop.top & ~1;
                int w = crop == null ? image.getWidth() : crop.right - left;
                int h = crop == null ? image.getHeight() : crop.bottom - top;
                info[0] = w;
                info[1] = h;
                info[3] = 3;
                info[4] = rotation;
                info[5] = 0;
                info[6] = matrix;
                info[7] = fullRange ? 1 : 0;
                info[8] = image.getTimestamp();
                ByteBuffer[] buffers = new ByteBuffer[3];
                for (int p = 0; p < 3; p++) {
                    buffers[p] = planes[p].getBuffer();
                    int row = planes[p].getRowStride(), pixel = planes[p].getPixelStride();
                    int skip = p == 0 ? top * row + left * pixel : (top / 2) * row + (left / 2) * pixel;
                    info[9 + p * 4] = buffers[p].position() + skip;
                    info[10 + p * 4] = row;
                    info[11 + p * 4] = pixel;
                    info[12 + p * 4] = Math.max(0, buffers[p].remaining() - skip);
                }
                return buffers;
            } catch (IllegalStateException error) {
                frameRelease();
                return null;
            }
        }

        void frameRelease() {
            synchronized (lock) {
                if (lent != null && lent != latest) lent.close();
                lent = null;
            }
        }
    }

    static Player player(int id) { return players.get(id); }

    public static int open(String source, boolean autoplay, boolean loop, boolean muted, float volume, float rate) {
        Player p = new Player(nextId++);
        players.put(p.id, p);
        p.wantsPlay = autoplay;
        p.loop = loop;
        p.muted = muted;
        p.volume = volume;
        p.rate = rate;
        p.open(source);
        return p.id;
    }

    public static void close(int id) {
        Player p = players.remove(id);
        if (p == null) return;
        if (p.screen != null) p.screen.player = null;
        p.close();
    }

    public static void play(int id) { Player p = player(id); if (p != null) p.play(); }

    public static void pause(int id) { Player p = player(id); if (p != null) p.pause(); }

    public static void seek(int id, double seconds) {
        Player p = player(id);
        if (p == null || !p.prepared) return;
        p.ended = false;
        long ms = (long)(seconds * 1000);
        if (Build.VERSION.SDK_INT >= 26) p.media.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        else p.media.seekTo((int)ms);
    }

    public static void set(int id, boolean loop, boolean muted, float volume, float rate) {
        Player p = player(id);
        if (p == null) return;
        p.loop = loop;
        p.muted = muted;
        p.volume = volume;
        p.rate = rate;
        if (!p.prepared) return;
        p.apply();
        if (p.media.isPlaying() && rate > 0) {
            try { p.media.setPlaybackParams(new PlaybackParams().setSpeed(rate)); }
            catch (IllegalArgumentException | IllegalStateException error) { /* refused */ }
        }
    }

    public static double position(int id) {
        Player p = player(id);
        if (p == null || !p.prepared) return 0;
        return p.media.getCurrentPosition() / 1000.0;
    }

    public static void frames(int id, boolean on, int maxWidth, int maxHeight) {
        Player p = player(id);
        if (p == null) return;
        p.framesOn = on;
        synchronized (p.lock) { p.posted = false; }
        p.route();
    }

    public static ByteBuffer[] frameAcquire(int id, long[] info) {
        Player p = player(id);
        return p == null ? null : p.frameAcquire(info);
    }

    public static void frameRelease(int id) {
        Player p = player(id);
        if (p != null) p.frameRelease();
    }

    // VideoView: a TextureView the player draws into, scaled to fit, cover or stretch.
    static final class Screen extends TextureView implements TextureView.SurfaceTextureListener {
        Player player;
        String fit = "contain";
        Surface surface;
        // Whether a canvas drew into `surface` (frames on), which keeps it from a MediaPlayer.
        volatile boolean canvasUsed;

        Screen(Context context) {
            super(context);
            setSurfaceTextureListener(this);
        }

        void show(int id) {
            Player next = player(id);
            if (next == player) return;
            if (player != null) { synchronized (viewLock) { player.viewSurface = null; } player.screen = null; player.route(); }
            player = next;
            if (player != null) { player.screen = this; player.viewSurface = surface; player.route(); }
            layoutPicture();
        }

        // A surface a canvas drew into stays connected to it: the MediaPlayer gets a new one.
        void renewSurface() {
            SurfaceTexture texture = getSurfaceTexture();
            synchronized (viewLock) {
                if (surface != null) surface.release();
                surface = texture != null ? new Surface(texture) : null;
                canvasUsed = false;
                if (player != null) player.viewSurface = surface;
            }
        }

        // The texture is stretched to the view; a scale about the center gives the picture
        // its aspect ratio back.
        void layoutPicture() {
            if (player == null || player.width == 0 || getWidth() == 0 || getHeight() == 0) return;
            boolean turned = player.rotation == 90 || player.rotation == 270;
            float w = turned ? player.height : player.width, h = turned ? player.width : player.height;
            Matrix matrix = new Matrix();
            if (!fit.equals("stretch")) {
                float sx = getWidth() / w, sy = getHeight() / h;
                float s = fit.equals("cover") ? Math.max(sx, sy) : Math.min(sx, sy);
                matrix.setScale(w * s / getWidth(), h * s / getHeight(), getWidth() / 2f, getHeight() / 2f);
            }
            setTransform(matrix);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            surface = new Surface(texture);
            if (player != null) { player.viewSurface = surface; player.route(); }
            layoutPicture();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { layoutPicture(); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            if (player != null) { synchronized (viewLock) { player.viewSurface = null; } player.route(); }
            synchronized (viewLock) {
                if (surface != null) { surface.release(); surface = null; }
                canvasUsed = false;
            }
            return true;
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

        @Override protected void onDetachedFromWindow() {
            if (player != null) { synchronized (viewLock) { player.viewSurface = null; } player.screen = null; player.route(); player = null; }
            super.onDetachedFromWindow();
        }
    }
}
