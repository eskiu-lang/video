// <video> on Android: the externs Eskiu calls, into EskiuVideo (Java) through JNI. A lent
// frame's planes are the direct buffers of a YUV_420_888 Image, read in place.
#include <jni.h>
#include <stdint.h>
#include "ui_native.h"

// EskiuVideo's class (a local reference the caller deletes), or null.
static jclass video_class(JNIEnv *env) { return ui_find_class("org.eskiu.video.EskiuVideo"); }

// A static void method of EskiuVideo taking the player's id.
static void call_id(const char *method, int id) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return;
    (*env)->CallStaticVoidMethod(env, type, (*env)->GetStaticMethodID(env, type, method, "(I)V"), (jint)id);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, type);
}

// EskiuVideo.yuvToRgba: a YUV_420_888 picture (three planes, each at an offset into its
// buffer with its row and pixel stride) to RGBA bytes in `out`, row after row, for the
// VideoView of a player whose frames are on. layout: the offset, row stride and pixel stride
// of Y, U and V, then width, height, matrix (1 BT.709) and full range (1).
static inline uint8_t clamp8(int v) { return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v); }

static void JNICALL yuv_to_rgba(JNIEnv *env, jclass type, jobject y, jobject u, jobject v, jintArray layout, jobject out) {
    (void)type;
    jint l[13];
    (*env)->GetIntArrayRegion(env, layout, 0, 13, l);
    const uint8_t *py = (*env)->GetDirectBufferAddress(env, y);
    const uint8_t *pu = (*env)->GetDirectBufferAddress(env, u);
    const uint8_t *pv = (*env)->GetDirectBufferAddress(env, v);
    uint8_t *po = (*env)->GetDirectBufferAddress(env, out);
    int w = l[9], h = l[10];
    if (!py || !pu || !pv || !po || w <= 0 || h <= 0 || (*env)->GetDirectBufferCapacity(env, out) < (jlong)w * h * 4) return;
    py += l[0];
    pu += l[3];
    pv += l[6];
    // Fixed point with 16 fraction bits, as <gpu/yuv> and VideoFrame_to_rgba convert.
    double kr = l[11] ? 0.2126 : 0.299, kb = l[11] ? 0.0722 : 0.114, kg = 1.0 - kr - kb;
    double ys = l[12] ? 1.0 : 255.0 / 219.0, cs = l[12] ? 1.0 : 255.0 / 224.0;
    int off = l[12] ? 0 : 16;
    int cy = (int)(ys * 65536.0 + 0.5);
    int crr = (int)(2.0 * (1.0 - kr) * cs * 65536.0 + 0.5);
    int cgb = (int)(2.0 * kb * (1.0 - kb) / kg * cs * 65536.0 + 0.5);
    int cgr = (int)(2.0 * kr * (1.0 - kr) / kg * cs * 65536.0 + 0.5);
    int cbb = (int)(2.0 * (1.0 - kb) * cs * 65536.0 + 0.5);
    for (int row = 0; row < h; row++) {
        const uint8_t *ly = py + (size_t)row * l[1];
        const uint8_t *lu = pu + (size_t)(row / 2) * l[4];
        const uint8_t *lv = pv + (size_t)(row / 2) * l[7];
        uint8_t *o = po + (size_t)row * w * 4;
        for (int x = 0; x < w; x++) {
            int yy = (ly[(size_t)x * l[2]] - off) * cy;
            int b = lu[(size_t)(x / 2) * l[5]] - 128;
            int r = lv[(size_t)(x / 2) * l[8]] - 128;
            o[0] = clamp8((yy + crr * r + 32768) >> 16);
            o[1] = clamp8((yy - cgb * b - cgr * r + 32768) >> 16);
            o[2] = clamp8((yy + cbb * b + 32768) >> 16);
            o[3] = 255;
            o += 4;
        }
    }
}

// Registers EskiuVideo's native methods once.
static void register_natives(JNIEnv *env, jclass type) {
    static int done;
    if (done) return;
    JNINativeMethod methods[] = {
        {"yuvToRgba", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;[ILjava/nio/ByteBuffer;)V", (void *)yuv_to_rgba},
    };
    if ((*env)->RegisterNatives(env, type, methods, 1) == 0) done = 1;
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

int video_native_open(const char *source, int autoplay, int loop, int muted, float volume, float rate) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return 0;
    register_natives(env, type);
    jmethodID method = (*env)->GetStaticMethodID(env, type, "open", "(Ljava/lang/String;ZZZFF)I");
    jstring text = (*env)->NewStringUTF(env, source ? source : "");
    jint id = (*env)->CallStaticIntMethod(env, type, method, text, (jboolean)(autoplay != 0), (jboolean)(loop != 0),
                                          (jboolean)(muted != 0), (jfloat)volume, (jfloat)rate);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); id = 0; }
    (*env)->DeleteLocalRef(env, text);
    (*env)->DeleteLocalRef(env, type);
    return id;
}

void video_native_close(int id) { call_id("close", id); }

void video_native_play(int id) { call_id("play", id); }

void video_native_pause(int id) { call_id("pause", id); }

void video_native_frame_release(int id) { call_id("frameRelease", id); }

void video_native_seek(int id, double seconds) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return;
    (*env)->CallStaticVoidMethod(env, type, (*env)->GetStaticMethodID(env, type, "seek", "(ID)V"), (jint)id, (jdouble)seconds);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, type);
}

void video_native_set(int id, int loop, int muted, float volume, float rate) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return;
    jmethodID method = (*env)->GetStaticMethodID(env, type, "set", "(IZZFF)V");
    (*env)->CallStaticVoidMethod(env, type, method, (jint)id, (jboolean)(loop != 0), (jboolean)(muted != 0), (jfloat)volume, (jfloat)rate);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, type);
}

double video_native_position(int id) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return 0;
    jdouble at = (*env)->CallStaticDoubleMethod(env, type, (*env)->GetStaticMethodID(env, type, "position", "(I)D"), (jint)id);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); at = 0; }
    (*env)->DeleteLocalRef(env, type);
    return at;
}

void video_native_frames(int id, int on, int max_width, int max_height) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return;
    jmethodID method = (*env)->GetStaticMethodID(env, type, "frames", "(IZII)V");
    (*env)->CallStaticVoidMethod(env, type, method, (jint)id, (jboolean)(on != 0), (jint)max_width, (jint)max_height);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, type);
}

// The planes' layout from Java, their addresses from the buffers. U and V samples two bytes
// apart in one buffer, a byte from each other, are one interleaved plane: NV12 when U comes
// first, NV21 when V does; one byte apart (pixel stride 1) they are I420; else the general
// three-plane layout.
int video_native_frame_acquire(int id, int64_t *info) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return 0;
    jmethodID method = (*env)->GetStaticMethodID(env, type, "frameAcquire", "(I[J)[Ljava/nio/ByteBuffer;");
    jlongArray values = (*env)->NewLongArray(env, 24);
    jobjectArray buffers = (jobjectArray)(*env)->CallStaticObjectMethod(env, type, method, (jint)id, values);
    (*env)->DeleteLocalRef(env, type);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); buffers = NULL; }
    if (!buffers) { (*env)->DeleteLocalRef(env, values); return 0; }
    jlong got[24];
    (*env)->GetLongArrayRegion(env, values, 0, 24, got);
    (*env)->DeleteLocalRef(env, values);
    uint8_t *data[3] = {0, 0, 0};
    int ok = 1;
    for (int p = 0; p < 3; p++) {
        jobject buffer = (*env)->GetObjectArrayElement(env, buffers, p);
        uint8_t *base = buffer ? (uint8_t *)(*env)->GetDirectBufferAddress(env, buffer) : NULL;
        if (buffer) (*env)->DeleteLocalRef(env, buffer);
        if (!base) { ok = 0; break; }
        data[p] = base + got[9 + p * 4];
    }
    (*env)->DeleteLocalRef(env, buffers);
    if (!ok) { video_native_frame_release(id); return 0; }
    for (int i = 0; i < 24; i++) info[i] = got[i];
    for (int p = 0; p < 3; p++) info[9 + p * 4] = (int64_t)(intptr_t)data[p];
    int64_t u_pixel = got[11 + 4], v_pixel = got[11 + 8];
    int64_t u_row = got[10 + 4], v_row = got[10 + 8];
    uint8_t *u = data[1], *v = data[2];
    int64_t u_size = got[12 + 4], v_size = got[12 + 8];
    if (u_pixel == 2 && v_pixel == 2 && u_row == v_row && (v == u + 1 || u == v + 1)) {
        uint8_t *first = v == u + 1 ? u : v;
        uint8_t *end_u = u + u_size, *end_v = v + v_size;
        info[2] = v == u + 1 ? 0 : 1;
        info[3] = 2;
        info[13] = (int64_t)(intptr_t)first;
        info[16] = (int64_t)((end_u > end_v ? end_u : end_v) - first);
        for (int i = 17; i < 21; i++) info[i] = 0;
    } else {
        info[2] = u_pixel == 1 && v_pixel == 1 ? 2 : 3;
        info[3] = 3;
    }
    return 1;
}
