// <video> on the web (and the desktop hosts, which run the web host): each player is an
// HTML <video>, which plays the sound. VideoView shows that element in its box; a player no
// view shows waits in a hidden holder in the page, so it keeps playing and decoding. With
// use_video_frames on, each new picture (requestVideoFrameCallback, else each animation
// frame) posts the player's id on "video_frame" when none is waiting, and video.js reads it
// through a canvas as RGBA (the web gives no YUV).
export function setup(ui) {
  const players = new Map();
  let nextId = 1;
  let holder = null;

  // Where players no view shows are kept: in the page, out of sight and out of the way.
  const hold = element => {
    if (!holder) {
      holder = document.createElement('div');
      Object.assign(holder.style, {position: 'fixed', left: '0', top: '0', width: '1px', height: '1px',
                                   overflow: 'hidden', opacity: '0', pointerEvents: 'none'});
      document.body.append(holder);
    }
    holder.append(element);
  };

  const emit = (id, what) => ui.emit('video', `${id} ${what}`);

  // Watches each new picture of a player whose frames are on.
  const watch = p => {
    const video = p.video;
    const next = () => {
      if (!p.frames.on || p.closed) return;
      if (video.requestVideoFrameCallback) p.frames.handle = video.requestVideoFrameCallback(seen);
      else p.frames.handle = requestAnimationFrame(() => seen(0, {mediaTime: video.currentTime}));
    };
    const seen = (now, meta) => {
      if (!p.frames.on || p.closed) return;
      if (video.readyState >= 2 && meta.mediaTime !== p.frames.last) {
        p.frames.last = meta.mediaTime;
        p.frames.time = meta.mediaTime;
        if (!p.frames.posted) { p.frames.posted = true; ui.emit('video_frame', String(p.id)); }
      }
      next();
    };
    next();
  };

  window.eskiuVideo = {
    open(source, autoplay, loop, muted, volume, rate) {
      const id = nextId++;
      const video = document.createElement('video');
      video.playsInline = true;
      video.preload = 'auto';
      // Asked with CORS so its pictures can be read for frames; a server that does not
      // answer CORS gets the video again without it, which plays but gives no frames.
      video.crossOrigin = 'anonymous';
      video.loop = loop;
      video.muted = muted;
      video.volume = Math.max(0, Math.min(1, volume));
      video.playbackRate = rate > 0 ? rate : 1;
      Object.assign(video.style, {width: '100%', height: '100%', objectFit: 'contain', background: '#000', display: 'block'});
      const p = {id, video, wantsPlay: autoplay, closed: false,
                 frames: {on: false, width: 0, height: 0, handle: 0, posted: false, last: -1, time: 0, canvas: null}};
      players.set(id, p);
      video.addEventListener('loadedmetadata', () => {
        const ms = isFinite(video.duration) ? Math.round(video.duration * 1000) : 0;
        emit(id, `ready ${ms} ${video.videoWidth} ${video.videoHeight}`);
        if (p.wantsPlay) window.eskiuVideo.play(id);
      });
      video.addEventListener('playing', () => emit(id, 'playing'));
      video.addEventListener('pause', () => { if (!video.ended) emit(id, 'paused'); });
      video.addEventListener('ended', () => emit(id, 'ended'));
      video.addEventListener('error', () => {
        if (video.crossOrigin !== null && !p.retried && !p.closed) {
          p.retried = true;
          video.removeAttribute('crossorigin');
          video.src = source;
          return;
        }
        const error = video.error;
        emit(id, `failed ${error && error.message ? error.message : 'the video could not be opened'}`);
      });
      hold(video);
      video.src = source;
      return id;
    },
    close(id) {
      const p = players.get(id);
      if (!p) return;
      p.closed = true;
      players.delete(id);
      p.video.pause();
      p.video.removeAttribute('src');
      p.video.load();
      p.video.remove();
    },
    play(id) {
      const p = players.get(id);
      if (!p) return;
      p.wantsPlay = true;
      if (p.video.readyState < 1) return;
      // A browser may refuse to start sound without a tap first: the player stays paused.
      const started = p.video.play();
      if (started) started.catch(() => emit(id, 'paused'));
    },
    pause(id) {
      const p = players.get(id);
      if (!p) return;
      p.wantsPlay = false;
      p.video.pause();
    },
    seek(id, seconds) { const p = players.get(id); if (p) p.video.currentTime = seconds; },
    set(id, loop, muted, volume, rate) {
      const p = players.get(id);
      if (!p) return;
      p.video.loop = loop;
      p.video.muted = muted;
      p.video.volume = Math.max(0, Math.min(1, volume));
      if (rate > 0) p.video.playbackRate = rate;
    },
    position(id) { const p = players.get(id); return p ? p.video.currentTime : 0; },
    frames(id, on, width, height) {
      const p = players.get(id);
      if (!p) return;
      Object.assign(p.frames, {on: !!on, width, height, posted: false, last: -1});
      if (on) watch(p);
    },
    // The player's current picture, scaled down to fit the asked size, as RGBA.
    grab(id) {
      const p = players.get(id);
      if (!p) return null;
      p.frames.posted = false;
      const video = p.video;
      if (!video.videoWidth || video.readyState < 2) return null;
      const f = p.frames;
      let scale = 1;
      if (f.width > 0) scale = Math.min(scale, f.width / video.videoWidth);
      if (f.height > 0) scale = Math.min(scale, f.height / video.videoHeight);
      const width = Math.max(1, Math.round(video.videoWidth * scale));
      const height = Math.max(1, Math.round(video.videoHeight * scale));
      const canvas = f.canvas || (f.canvas = document.createElement('canvas'));
      if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
      const context = canvas.getContext('2d', {willReadFrequently: true});
      context.drawImage(video, 0, 0, width, height);
      let data;
      try { data = context.getImageData(0, 0, width, height).data; }
      catch (error) { return null; }   // a video from another origin without CORS cannot be read
      return {width, height, time: Math.round(f.time * 1e9), data};
    },
  };

  ui.registerView('video', {
    create() {
      const box = document.createElement('div');
      Object.assign(box.style, {width: '100%', height: '100%', background: '#000', overflow: 'hidden'});
      let shown = null;
      let fit = 'contain';
      const place = () => {
        if (!shown) return;
        shown.video.style.objectFit = fit === 'cover' ? 'cover' : fit === 'stretch' ? 'fill' : 'contain';
        if (shown.video.parentNode !== box) box.append(shown.video);
      };
      const leave = () => {
        if (shown && !shown.closed && shown.video.parentNode === box) hold(shown.video);
        shown = null;
      };
      return {
        element: box,
        set(name, value) {
          if (name === 'player') { leave(); shown = players.get(Number(value)) || null; place(); }
          else if (name === 'fit') { fit = value || 'contain'; place(); }
        },
        dispose() { leave(); },
      };
    },
  });
}
