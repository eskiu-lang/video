// <video> on iOS: each player is an AVPlayer (which plays the sound) with its item's state
// on the "video" channel (src/video.esk). VideoView is an AVPlayerLayer. Frames
// (use_video_frames) come from an AVPlayerItemVideoOutput polled by a CADisplayLink, as the
// decoder's own NV12 pixel buffers lent to Eskiu by pointer. Everything runs on the main
// thread, where Eskiu calls the externs.
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import "ui_native.h"

static void emit(int vid, NSString *what) {
    ui_native_emit("video", [NSString stringWithFormat:@"%d %@", vid, what].UTF8String);
}

@interface EskiuVideoPlayer : NSObject
@property(nonatomic) int vid;
@property(nonatomic, strong) AVPlayer *player;
@property(nonatomic, strong) AVPlayerItem *item;
@property(nonatomic, strong) AVPlayerItemVideoOutput *output;
@property(nonatomic, strong) CADisplayLink *link;
@property(nonatomic, strong) id endObserver;
@property(nonatomic) BOOL loop, wantsPlay, ready, ended, framesOn;
@property(nonatomic) float rate;
@property(nonatomic) int rotation, maxWidth, maxHeight;
@property(nonatomic) CGSize natural;
@end

static NSMutableDictionary<NSNumber *, EskiuVideoPlayer *> *players;
static int nextId = 1;

// How far the track's transform turns the picture clockwise: 0, 90, 180 or 270.
static int trackRotation(CGAffineTransform t) {
    int degrees = (int)lround(atan2(t.b, t.a) * 180.0 / M_PI);
    return ((degrees % 360) + 360) % 360 / 90 * 90;
}

@implementation EskiuVideoPlayer {
    // The newest frame, the one lent to Eskiu, the newest one's time, and whether its id is
    // posted and not yet acquired.
    CVPixelBufferRef latest, lent;
    int64_t latestTime;
    BOOL posted;
}
- (void)observeValueForKeyPath:(NSString *)path ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if ([path isEqualToString:@"status"]) {
        if (self.item.status == AVPlayerItemStatusFailed) {
            NSString *why = self.item.error.localizedDescription ?: @"the video could not be opened";
            emit(self.vid, [@"failed " stringByAppendingString:why]);
        } else if (self.item.status == AVPlayerItemStatusReadyToPlay && !self.ready) {
            self.ready = YES;
            [self described];
        }
    } else if ([path isEqualToString:@"timeControlStatus"]) {
        AVPlayerTimeControlStatus s = self.player.timeControlStatus;
        if (s == AVPlayerTimeControlStatusPlaying) { self.ended = NO; emit(self.vid, @"playing"); }
        else if (s == AVPlayerTimeControlStatusPaused && self.ready && !self.ended) emit(self.vid, @"paused");
    }
}
// The size, length and rotation once the item is ready; then autoplay.
- (void)described {
    AVAssetTrack *track = [self.item.asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
    CGSize size = self.item.presentationSize;
    self.rotation = 0;
    if (track) {
        self.rotation = trackRotation(track.preferredTransform);
        size = track.naturalSize;
    }
    self.natural = size;
    int w = (int)size.width, h = (int)size.height;
    if (self.rotation == 90 || self.rotation == 270) { int t = w; w = h; h = t; }
    CMTime duration = self.item.duration;
    long long ms = CMTIME_IS_NUMERIC(duration) && !CMTIME_IS_INDEFINITE(duration) ? (long long)(CMTimeGetSeconds(duration) * 1000.0) : 0;
    emit(self.vid, [NSString stringWithFormat:@"ready %lld %d %d", ms, w, h]);
    if (self.framesOn) [self startFrames];
    if (self.wantsPlay) [self play];
}
- (void)play {
    self.wantsPlay = YES;
    if (!self.ready) return;
    if (self.ended || (CMTIME_IS_NUMERIC(self.item.duration) &&
                       CMTimeCompare(self.player.currentTime, self.item.duration) >= 0)) {
        [self.player seekToTime:kCMTimeZero];
    }
    self.ended = NO;
    self.player.rate = self.rate > 0 ? self.rate : 1;
}
- (void)pause {
    self.wantsPlay = NO;
    [self.player pause];
}
- (void)reachedEnd {
    if (self.loop) {
        [self.player seekToTime:kCMTimeZero];
        self.player.rate = self.rate > 0 ? self.rate : 1;
        return;
    }
    self.ended = YES;
    self.wantsPlay = NO;
    emit(self.vid, @"ended");
}

// Frames. The output gives the decoder's buffers (video-range NV12, scaled down to the
// asked size when there is one); the display link takes the newest for the current time
// into `latest` and posts the player's id when none is posted since the last acquire.
- (void)startFrames {
    [self stopFrames];
    if (!self.ready) return;
    NSMutableDictionary *attributes = [@{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)} mutableCopy];
    CGSize size = self.natural;
    if (size.width > 0 && size.height > 0 && (self.maxWidth > 0 || self.maxHeight > 0)) {
        double scale = 1.0;
        if (self.maxWidth > 0) scale = MIN(scale, self.maxWidth / size.width);
        if (self.maxHeight > 0) scale = MIN(scale, self.maxHeight / size.height);
        if (scale < 1.0) {
            attributes[(id)kCVPixelBufferWidthKey] = @((int)lround(size.width * scale) & ~1);
            attributes[(id)kCVPixelBufferHeightKey] = @((int)lround(size.height * scale) & ~1);
        }
    }
    self.output = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:attributes];
    [self.item addOutput:self.output];
    self.link = [CADisplayLink displayLinkWithTarget:self selector:@selector(tick:)];
    [self.link addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
}
- (void)stopFrames {
    [self.link invalidate];
    self.link = nil;
    if (self.output) [self.item removeOutput:self.output];
    self.output = nil;
    if (latest) CVPixelBufferRelease(latest);
    latest = NULL;
    posted = NO;
}
- (void)tick:(CADisplayLink *)link {
    CMTime time = [self.output itemTimeForHostTime:CACurrentMediaTime()];
    if (![self.output hasNewPixelBufferForItemTime:time]) return;
    CMTime shown = kCMTimeInvalid;
    CVPixelBufferRef buffer = [self.output copyPixelBufferForItemTime:time itemTimeForDisplay:&shown];
    if (!buffer) return;
    if (latest) CVPixelBufferRelease(latest);
    latest = buffer;
    CMTime at = CMTIME_IS_NUMERIC(shown) ? shown : time;
    latestTime = CMTIME_IS_NUMERIC(at) ? (int64_t)(CMTimeGetSeconds(at) * 1e9) : 0;
    if (!posted) {
        posted = YES;
        ui_native_emit("video_frame", [NSString stringWithFormat:@"%d", self.vid].UTF8String);
    }
}
- (void)giveBack {
    if (!lent) return;
    CVPixelBufferUnlockBaseAddress(lent, kCVPixelBufferLock_ReadOnly);
    CVPixelBufferRelease(lent);
    lent = NULL;
}
- (int)acquire:(int64_t *)info {
    [self giveBack];
    posted = NO;
    CVPixelBufferRef buffer = latest;
    if (!buffer) return 0;
    if (CVPixelBufferGetPlaneCount(buffer) != 2 ||
        CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return 0;
    CVPixelBufferRetain(buffer);
    lent = buffer;
    for (int i = 0; i < 24; i++) info[i] = 0;
    info[0] = (int64_t)CVPixelBufferGetWidth(buffer);
    info[1] = (int64_t)CVPixelBufferGetHeight(buffer);
    info[2] = 0;
    info[3] = 2;
    info[4] = self.rotation;
    CFTypeRef matrix = CVBufferCopyAttachment(buffer, kCVImageBufferYCbCrMatrixKey, NULL);
    if (matrix) info[6] = CFEqual(matrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2) ? 1 : 0;
    else info[6] = info[1] >= 720 ? 1 : 0;
    if (matrix) CFRelease(matrix);
    info[7] = CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange;
    info[8] = latestTime;
    for (int p = 0; p < 2; p++) {
        size_t row = CVPixelBufferGetBytesPerRowOfPlane(buffer, p);
        info[9 + p * 4] = (int64_t)(intptr_t)CVPixelBufferGetBaseAddressOfPlane(buffer, p);
        info[10 + p * 4] = (int64_t)row;
        info[11 + p * 4] = p == 0 ? 1 : 2;
        info[12 + p * 4] = (int64_t)(row * CVPixelBufferGetHeightOfPlane(buffer, p));
    }
    return 1;
}
- (void)close {
    [self stopFrames];
    [self giveBack];
    [self.player pause];
    [self.item removeObserver:self forKeyPath:@"status"];
    [self.player removeObserver:self forKeyPath:@"timeControlStatus"];
    if (self.endObserver) [NSNotificationCenter.defaultCenter removeObserver:self.endObserver];
    self.endObserver = nil;
    [self.player replaceCurrentItemWithPlayerItem:nil];
}
@end

static EskiuVideoPlayer *lookup(int vid) { return players[@(vid)]; }

int video_native_open(const char *source, int autoplay, int loop, int muted, float volume, float rate) {
    if (!players) {
        players = [NSMutableDictionary new];
        // Video plays its sound with the ring switch on silent, as video apps do.
        [AVAudioSession.sharedInstance setCategory:AVAudioSessionCategoryPlayback error:nil];
    }
    NSString *text = [NSString stringWithUTF8String:source ?: ""];
    NSURL *url = [text containsString:@"://"] ? [NSURL URLWithString:text] : [NSURL fileURLWithPath:text];
    EskiuVideoPlayer *p = [EskiuVideoPlayer new];
    p.vid = nextId++;
    players[@(p.vid)] = p;
    if (!url) {
        int vid = p.vid;
        dispatch_async(dispatch_get_main_queue(), ^{ emit(vid, @"failed not a URL or a path"); });
        return p.vid;
    }
    p.item = [AVPlayerItem playerItemWithURL:url];
    p.player = [AVPlayer playerWithPlayerItem:p.item];
    p.player.actionAtItemEnd = AVPlayerActionAtItemEndPause;
    p.player.muted = muted != 0;
    p.player.volume = volume;
    p.loop = loop != 0;
    p.rate = rate;
    p.wantsPlay = autoplay != 0;
    [p.item addObserver:p forKeyPath:@"status" options:0 context:NULL];
    [p.player addObserver:p forKeyPath:@"timeControlStatus" options:0 context:NULL];
    __weak EskiuVideoPlayer *weak = p;
    p.endObserver = [NSNotificationCenter.defaultCenter addObserverForName:AVPlayerItemDidPlayToEndTimeNotification
        object:p.item queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) { [weak reachedEnd]; }];
    return p.vid;
}

void video_native_close(int vid) {
    EskiuVideoPlayer *p = lookup(vid);
    if (!p) return;
    [p close];
    [players removeObjectForKey:@(vid)];
}

void video_native_play(int vid) { [lookup(vid) play]; }

void video_native_pause(int vid) { [lookup(vid) pause]; }

void video_native_seek(int vid, double seconds) {
    EskiuVideoPlayer *p = lookup(vid);
    if (!p) return;
    p.ended = NO;
    [p.player seekToTime:CMTimeMakeWithSeconds(seconds, 600) toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
}

void video_native_set(int vid, int loop, int muted, float volume, float rate) {
    EskiuVideoPlayer *p = lookup(vid);
    if (!p) return;
    p.loop = loop != 0;
    p.player.muted = muted != 0;
    p.player.volume = volume;
    p.rate = rate;
    if (p.player.rate != 0 && rate > 0) p.player.rate = rate;
}

double video_native_position(int vid) {
    EskiuVideoPlayer *p = lookup(vid);
    if (!p) return 0;
    double t = CMTimeGetSeconds(p.player.currentTime);
    return isfinite(t) ? t : 0;
}

void video_native_frames(int vid, int on, int max_width, int max_height) {
    EskiuVideoPlayer *p = lookup(vid);
    if (!p) return;
    p.framesOn = on != 0;
    p.maxWidth = max_width;
    p.maxHeight = max_height;
    if (p.framesOn) [p startFrames];
    else [p stopFrames];
}

int video_native_frame_acquire(int vid, int64_t *info) {
    EskiuVideoPlayer *p = lookup(vid);
    return p ? [p acquire:info] : 0;
}

void video_native_frame_release(int vid) { [lookup(vid) giveBack]; }

// VideoView: an AVPlayerLayer showing the player named by the "player" prop.
@interface EskiuVideoView : UIView
@end

@implementation EskiuVideoView
+ (Class)layerClass { return AVPlayerLayer.class; }
@end

UI_NATIVE_SETUP(video) {
    ui_register_view(@"video", ^UIView *(UiViewEmit emit) {
        EskiuVideoView *view = [EskiuVideoView new];
        view.backgroundColor = UIColor.blackColor;
        ((AVPlayerLayer *)view.layer).videoGravity = AVLayerVideoGravityResizeAspect;
        return view;
    }, ^(UIView *view, NSString *name, NSString *value) {
        AVPlayerLayer *layer = (AVPlayerLayer *)view.layer;
        if ([name isEqualToString:@"player"]) {
            layer.player = lookup(value.intValue).player;
        } else if ([name isEqualToString:@"fit"]) {
            layer.videoGravity = [value isEqualToString:@"cover"] ? AVLayerVideoGravityResizeAspectFill
                : [value isEqualToString:@"stretch"] ? AVLayerVideoGravityResize : AVLayerVideoGravityResizeAspect;
        }
    });
}
