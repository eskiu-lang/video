<p align="center">
  <img src="https://raw.githubusercontent.com/eskiu-lang/eskiu/main/assets/logo.png" alt="Eskiu, a kitten with a ball of yarn" width="140">
</p>

<h2 align="center">video</h2>
<p align="center">Video playback for apps on eskiu-lang/ui, with the decoded frames in Eskiu.</p>
<p align="center"><sub>Part of the <a href="https://github.com/eskiu-lang">Eskiu</a> project</sub></p>
<p align="center">
  <a href="https://eskiu-lang.org/ui.html">eskiu-lang.org/ui</a> &nbsp;&middot;&nbsp;
  <a href="#use-it">Use it</a> &nbsp;&middot;&nbsp;
  <a href="examples/player">Example</a> &nbsp;&middot;&nbsp;
  <a href="https://github.com/eskiu-lang/ui">ui</a>
</p>

<p align="center">
  <a href="https://github.com/eskiu-lang/video/actions/workflows/ci.yml"><img src="https://github.com/eskiu-lang/video/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/version-0.1.0-6448d4" alt="Version 0.1.0">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License: MIT"></a>
</p>

---

An app plays a video file or stream in the platform's own player (AVPlayer on iOS,
MediaPlayer on Android, an HTML `<video>` on the web and the desktop), with its sound, and
shows it in a `VideoView`. `use_video_frames` hands the decoded pictures to Eskiu, lent by
pointer as YUV planes (RGBA on the web) in the same layout as ui-camera's frames, so
[`<gpu/yuv>`](https://github.com/eskiu-lang/gpu/blob/main/docs/yuv.md) draws them on the GPU
or `VideoFrame_to_rgba` converts them on the CPU.

## Add it

In an app made with `ui new`:

```bash
eskiuc add video --git https://github.com/eskiu-lang/video --rev v0.1.0
```

## Use it

```eskiu
import <video>;

void Clip() {
    *VideoPlayer player = use_video("intro.mp4", VideoOptions{autoplay: true, loop: true});
    use_video_frames(player, VideoFrameOptions{max_width: 640}, void(*VideoFrame frame) {
        // Borrowed for this call: upload it (<gpu/yuv>), convert it or copy it.
    });
    Column(Style{padding: 20, gap: 12}) {
        VideoView(Style{height: 220}, player, VideoContain);
        Button(player.status() == VideoPlaying ? "Pause" : "Play") {
            if (player.status() == VideoPlaying) { player.pause(); } else { player.play(); }
        }
    }
}
```

## Documentation

- [Playback](docs/playback.md): `use_video`, sources, options, status, `VideoView`
- [Decoded frames](docs/frames.md): `use_video_frames`, formats, the GPU and the CPU
- [Platforms, the example and the tests](docs/README.md)
- [API reference](docs/api.md): every public function and type

## License

MIT. See [LICENSE](LICENSE).
