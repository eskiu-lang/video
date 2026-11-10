# Changelog

All notable changes to this package are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - 2026-11-07

### Added

- `use_video(source, VideoOptions)`: a video file, an asset or an HTTP(S) stream in the
  platform's player (AVPlayer on iOS, MediaPlayer on Android, `<video>` on the web and the
  desktop hosts), with its sound. `VideoPlayer` has `status`, `duration`, `width`, `height`,
  `error` (reactive), `position`, `play`, `pause` and `seek`; `VideoOptions` sets autoplay,
  loop, muted, volume and rate. See [docs/playback.md](docs/playback.md).
- `VideoView(Style, *VideoPlayer, VideoFit)`: the platform's own view of a player
  (contain, cover or stretch).
- `use_video_frames(*VideoPlayer, VideoFrameOptions, fn(*VideoFrame))`: decoded frames lent
  by pointer on the UI thread, newest frame wins. `VideoFrame` has the layout of ui-camera's
  `CameraFrame` and gpu's `GpuYuvImage` (NV12 on iOS, `YUV_420_888` layouts on Android, RGBA
  on the web). `VideoFrame_to_rgba`, `video_yuv_coeffs` and `video_yuv_to_rgb` convert on
  the CPU. See [docs/frames.md](docs/frames.md).

### Fixed

- Android: a `VideoView` stayed black while `use_video_frames` was on for its player (the
  `MediaPlayer` has one output, and the frames took it). Each frame is now converted to RGB in
  C and drawn into the view as well, upright, so the view and the frames work together as on
  iOS and the web.
