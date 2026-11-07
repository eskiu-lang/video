# video documentation

Guides and reference for video. The [README](../README.md) has the overview.

<!-- pages -->
- [Decoded frames](frames.md)
- [Playback](playback.md)
- [API reference](api.md): every public function and type
<!-- /pages -->

## Platforms

| Platform | Player | `VideoView` | Frames |
|---|---|---|---|
| iOS | `AVPlayer` | `AVPlayerLayer` | `AVPlayerItemVideoOutput`, NV12 |
| Android | `MediaPlayer` | `TextureView` | `YUV_420_888` `ImageReader` (the view stays black while frames are on) |
| Web | `<video>` | the `<video>` element | canvas read back as RGBA |
| macOS, Linux, Windows | the web host's `<video>` (`WKWebView`, WebKitGTK, WebView2) | the same | the same |

The desktop hosts run the web host in a web view, so they use the web's code: what a desktop
plays is what its web view plays (WebKitGTK needs the GStreamer plugins for the codec).

Checked so far: the Eskiu side on this computer (`eskiuc test`), the example built for the
web and run in headless Chrome (playback, `VideoView`, frames at 30 a second, pause), and
built for the iOS simulator and run there (playback, looping, `VideoView`, NV12 frames). The
Android build compiles and packs; it has not yet run on a device, so the `MediaPlayer` to
`ImageReader` path is untested there.

## The example

`examples/player` plays a 4-second test clip from its `assets/` in a `VideoView`, with play,
pause, a jump back, a loop switch, the time, and its frames counted with the color at the
middle of the picture:

```bash
cd examples/player
ui run web    # or ios, android
```

## Tests

`eskiuc test` runs on this computer with stand-ins for the platform's side.
`tests/video.esk` checks sources, the open with its options, each event (and that other
players' are ignored), play, pause, seek, new options without a reopen, a new source closing
the old player, a failure's message, and the close at unmount. `tests/frames.esk` checks the
YUV to RGB conversion against the reference formulas, the checks on lent frames, each layout
turned upright, and `use_video_frames` per player, across a change of source.
