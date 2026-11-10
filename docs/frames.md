# Decoded frames

`use_video_frames` hands a player's decoded pictures to Eskiu as the video plays: YUV planes
on iOS and Android, RGBA on the web and the desktop. It is for code that looks at the
pictures (a detector, a filter, a texture for a 3D scene), as opposed to `VideoView`, which
shows them with no Eskiu code in between.

```eskiu
import <video>;

uint8[640 * 640 * 4] pixels;

void Analyzer() {
    *VideoPlayer player = use_video("walk.mp4", VideoOptions{autoplay: true, muted: true});
    use_video_frames(player, VideoFrameOptions{max_width: 640, max_height: 640}, void(*VideoFrame frame) {
        // Borrowed for this call: read it, convert it or copy it, then return.
        int w = frame.out_width(true);
        int h = frame.out_height(true);
        if (w * h <= 640 * 640) {
            frame.to_rgba(&pixels[0], w * 4, true);
            detect(&pixels[0], w, h);
        }
    });
    VideoView(Style{height: 240}, player, VideoContain);
}
```

## How a frame gets to Eskiu

This is the path ui-camera's live frames take. The pixels are never copied into a message.
The native side keeps the newest decoded picture of each player whose frames are on, and
posts the player's id on the `video_frame` channel when one is waiting and none is posted
yet. When the event reaches Eskiu on the UI thread, `use_video_frames` borrows that picture
through `video_native_frame_acquire`, which fills a small array with its size, layout and
plane addresses; the handler reads the platform's own buffer by pointer, and
`video_native_frame_release` gives it back when the handler returns.

| Platform | Source | Buffer lent to Eskiu |
|---|---|---|
| iOS | An `AVPlayerItemVideoOutput` on the player's item, read on each display refresh (`CADisplayLink`) when it has a new picture | The decoder's `CVPixelBuffer` (`420YpCbCr8BiPlanarVideoRange`, scaled down to the asked size by the output), base address locked read-only for the call: NV12, limited range |
| Android | The `MediaPlayer` draws into a `YUV_420_888` `ImageReader`; a `VideoView` showing the player gets each frame converted to RGB and drawn with a canvas | The `Image`'s planes (direct buffers, read in place, from its crop rectangle): NV12, NV21, I420 or the general three-plane layout, as the device's decoder gives it |
| Web, desktop | The player's `<video>`, on each new picture (`requestVideoFrameCallback`, else each animation frame) | The picture drawn into a canvas, scaled down to the asked size, and read back as RGBA into one buffer per player in the app's memory, reused |

A paused player gives no new frames. A frame that arrives while the handler runs, or while
the event waits behind other work on the UI thread, replaces the one waiting, so a slow
handler gets fewer, newer frames and nothing queues up. The platform holds at most two
pictures for Eskiu per player: the one waiting and the one lent.

The handler runs on the UI thread, like every `ui` handler. Heavy work there holds up the
interface: convert or copy what you need and hand it to a thread of your own. A frame's
pointers are not valid after the handler returns.

## The frame

`VideoFrame` has the layout of ui-camera's `CameraFrame`, with the same format and matrix
numbers:

```eskiu
enum VideoFormat { VideoNV12, VideoNV21, VideoI420, VideoYUV420, VideoRGBA }
enum VideoMatrix { VideoBT601, VideoBT709 }

struct VideoPlane { *uint8 data; int row_stride; int pixel_stride; int64 size; }

struct VideoFrame {
    int width; int height; int format; int plane_count; VideoPlane[3] planes;
    int rotation; int64 timestamp_ns; bool front; int matrix; bool full_range;
}
```

- **Size.** `width` and `height` are the picture as decoded. `VideoFrameOptions` asks for
  at most `max_width` x `max_height` (0 for no limit), keeping the aspect ratio: iOS and the
  web scale down to it; Android gives the decoded size.
- **Layout.** `format` names it and `plane_count` planes are set; the table of layouts is
  in ui-camera's [frames guide](https://github.com/eskiu-lang/ui-camera/blob/main/docs/frames.md#the-api).
  Rows are often longer than the width (`row_stride`), and chroma planes are half the width
  and height, rounded up.
- **Orientation.** A phone's portrait video is stored landscape with a rotation in its file.
  `rotation` (0, 90, 180 or 270) is how far to turn the frame clockwise to be upright (iOS
  from the track's transform, Android from the container); the web's frames are already
  upright. `VideoView` and the size the player reports are upright.
- **Color.** `matrix` and `full_range` say how the YUV maps to RGB. iOS reads the buffer's
  matrix attachment and gives limited range, as the decoder does. Android reads the
  container's color standard and range when it has them, else takes BT.709 limited range
  for pictures 720 rows high or more and BT.601 limited range under that. The web's RGBA is
  the browser's own conversion.
- **Time.** `timestamp_ns` is the frame's time in the video (iOS, the web) or the decoder's
  presentation time (Android), in nanoseconds.
- `front` is always false; it keeps the layout shared with camera frames.

## On the GPU

A `*VideoFrame` cast to a `*GpuYuvImage` goes straight to
[`<gpu/yuv>`](https://github.com/eskiu-lang/gpu/blob/main/docs/yuv.md) in the `gpu` package,
which uploads the planes as they are (NV12 as an R8 and an RG8 texture, with the row
stride) and converts to RGB in a shader, turned upright:

```eskiu
use_video_frames(player, VideoFrameOptions{}, void(*VideoFrame frame) {
    if (yuv.upload((*GpuYuvImage)frame).ok) { redraw = true; }
});
```

The planes are read while `upload` runs, so the frame can be given back right after.

## On the CPU

`VideoFrame_to_rgba(frame, out, out_stride, upright)` writes RGBA (alpha 255) in pure
Eskiu, with the frame's own matrix and range, from any of the layouts, turned upright when
asked; `out_width(upright)` and `out_height(upright)` give the size `out` needs. It is fixed
point (16 fraction bits); `video_yuv_coeffs` and `video_yuv_to_rgb` convert single pixels,
and `tests/frames.esk` checks them against the BT.601 and BT.709 formulas within one step.
