# Playback

`use_video` opens a video in the platform's own player and keeps it open while the
component that calls it is mounted. The player decodes the picture and plays the sound; the
app shows it with a `VideoView`, reads its decoded frames with
[`use_video_frames`](frames.md), or both.

```eskiu
import <video>;

void Trailer(string url) {
    *VideoPlayer player = use_video(url, VideoOptions{autoplay: true});
    *Signal<int> tick = use_signal<int>(0);
    use_interval(500, void() { tick.set(tick.value + 1); });
    Column(Style{gap: 8}) {
        VideoView(Style{height: 200}, player, VideoCover);
        tick.get();
        String time;
        String_format(&time, "%.0f of %.0f s", player.position(), player.duration());
        Text(time.data);
        String_free(&time);
        if (player.status() == VideoFailed) { Text(player.error()); }
    }
}
```

## Sources

The source is a string:

| Source | What it opens |
|---|---|
| `https://...`, `http://...` | A file or stream over HTTP(S): MP4 and MOV on every platform, HLS (`.m3u8`) on iOS, Android and Safari; what else depends on the platform's player. |
| `file:///...`, `/var/mobile/...` | A file by URL or absolute path, such as one the app saved in `ui_data_dir()`. |
| `blob:...`, `data:...` | On the web, a URL the page made. |
| `intro.mp4`, `clips/a.mov` | A file of the app's `assets/`: its path in the bundle on iOS and on Android (`ui_asset_path`, which copies it out of the APK once), and its URL, relative to the page, on the web and the desktop. |

`video_resolve(source)` turns a source into what the platform gets. On the web a remote
video is asked for with CORS, so its frames can be read; a server that does not answer
CORS gets the request again without it, and then the video plays but gives no frames.

A new source on a later render closes the player and opens the new one. The player closes
when the scope unmounts.

## Options

`VideoOptions` sets how it plays; a change from one render to the next is applied to the
player that is open, without opening it again:

| Field | Default | What it does |
|---|---|---|
| `autoplay` | false | Starts playing once the video is ready. |
| `loop` | false | Starts again at the end, with no `VideoEnded`. |
| `muted` | false | No sound. |
| `volume` | 1.0 | From 0 to 1. |
| `rate` | 1.0 | The speed: 0.5 is half, 2 twice (on Android, a speed the device takes). |

A browser may refuse to start a video with sound before the user has tapped the page:
`autoplay` (or `play()`) then leaves it `VideoPaused`, and a later `play()` from a tap
starts it. A muted video always starts.

## Status

| `status()` | When |
|---|---|
| `VideoLoading` | Opening the source. |
| `VideoReady` | Ready, not started (`autoplay` off). `duration()`, `width()` and `height()` are set. |
| `VideoPlaying` | Playing. |
| `VideoPaused` | Paused by `pause()`, or a start the browser refused. |
| `VideoEnded` | At the end, with `loop` off. `play()` starts it again from the beginning. |
| `VideoFailed` | The source cannot be played: `error()` has the platform's message. |

`status()`, `duration()` (seconds, 0 for a live stream), `width()`, `height()` (the
picture as it is shown, turned upright) and `error()` subscribe the component that reads
them, so it renders again when they change. `position()` (seconds) asks the player at the
call; it changes all the time, so a component that shows it renders on a timer
(`use_interval`) or each display frame (`use_frame`).

`play()`, `pause()` and `seek(seconds)` act on the player at once. A seek goes to the exact
frame (on Android 7, to a key frame near it).

## VideoView

`VideoView(style, player, fit)` is the platform's own view of the player (an
`AVPlayerLayer`, a `TextureView`, the `<video>` element), so Eskiu never touches the frames
it shows. It is a leaf: give it a size. `fit` is `VideoContain` (the whole picture, bars
where it does not fill the box), `VideoCover` (fills the box, cropping) or `VideoStretch`.
Show a player in one `VideoView` at a time.

On Android, a player sends its picture to one place: while `use_video_frames` is on for it,
the frames get the picture and its `VideoView` stays black. Draw the frames with
[`<gpu/yuv>`](https://github.com/eskiu-lang/gpu/blob/main/docs/yuv.md) there instead. iOS
and the web show the view and give the frames at the same time.

## Sound

The platform plays the sound with the picture. On iOS the first player sets the app's audio
session to the playback category, so a video is heard with the ring switch on silent, as in
video apps. On Android the player plays as media (`USAGE_MEDIA`). `muted` and `volume` set
the player's own level, under the system's.
