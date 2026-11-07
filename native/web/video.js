// <video> on the web: the externs, into window.eskiuVideo (video.mjs). A frame is the
// player's current picture drawn into an RGBA buffer in the app's memory (one per player,
// kept from one frame to the next), described in `info` (24 int64 values,
// video_frame_read's layout in src/video.esk).
mergeInto(LibraryManager.library, {
  video_native_open: function(source, autoplay, loop, muted, volume, rate) {
    return window.eskiuVideo.open(UTF8ToString(source), !!autoplay, !!loop, !!muted, volume, rate);
  },
  video_native_close__deps: ['free'],
  video_native_close: function(id) {
    window.eskiuVideo.close(id);
    const buffers = Module['videoFrames'];
    if (buffers && buffers[id]) { _free(buffers[id].pointer); delete buffers[id]; }
  },
  video_native_play: function(id) { window.eskiuVideo.play(id); },
  video_native_pause: function(id) { window.eskiuVideo.pause(id); },
  video_native_seek: function(id, seconds) { window.eskiuVideo.seek(id, seconds); },
  video_native_set: function(id, loop, muted, volume, rate) { window.eskiuVideo.set(id, !!loop, !!muted, volume, rate); },
  video_native_position: function(id) { return window.eskiuVideo.position(id); },
  video_native_frames: function(id, on, width, height) { window.eskiuVideo.frames(id, on, width, height); },
  video_native_frame_acquire__deps: ['malloc', 'free'],
  video_native_frame_acquire: function(id, info) {
    const frame = window.eskiuVideo.grab(id);
    if (!frame) return 0;
    const bytes = frame.width * frame.height * 4;
    const buffers = Module['videoFrames'] || (Module['videoFrames'] = {});
    const state = buffers[id] || (buffers[id] = {pointer: 0, bytes: 0});
    if (state.bytes < bytes) {
      if (state.pointer) _free(state.pointer);
      state.pointer = _malloc(bytes);
      state.bytes = state.pointer ? bytes : 0;
      if (!state.pointer) return 0;
    }
    HEAPU8.set(frame.data, state.pointer);
    const put = (index, value) => {
      HEAP32[(info >> 2) + index * 2] = value % 4294967296;
      HEAP32[(info >> 2) + index * 2 + 1] = Math.floor(value / 4294967296);
    };
    for (let i = 0; i < 24; i++) put(i, 0);
    put(0, frame.width);
    put(1, frame.height);
    put(2, 4);
    put(3, 1);
    put(7, 1);
    put(8, frame.time);
    put(9, state.pointer);
    put(10, frame.width * 4);
    put(11, 4);
    put(12, bytes);
    return 1;
  },
  video_native_frame_release: function(id) {},
});
