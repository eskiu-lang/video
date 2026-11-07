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

int video_native_open(const char *source, int autoplay, int loop, int muted, float volume, float rate) {
    JNIEnv *env = ui_jni_env();
    jclass type = video_class(env);
    if (!type) return 0;
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
