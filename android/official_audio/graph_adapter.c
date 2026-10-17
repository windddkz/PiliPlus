#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>
#include <android/log.h>
#include <libavcodec/avcodec.h>

#define FIELD(p, o, T) (*(T *)((char *)(p) + (o)))
typedef struct {
    int32_t status;
    uint32_t padding;
    void *frame;
    int64_t serial, queue_serial;
    uint64_t extra[5];
} SourceResult;
_Static_assert(sizeof(SourceResult) == 72, "Original source result ABI");
_Static_assert(offsetof(SourceResult, frame) == 8, "Original source frame offset");
typedef SourceResult (*Read)(void *);
typedef void (*Ref)(void *, const void *);
extern void graph_make_frame(void **, int, uint64_t, int, int, uintptr_t);
static uintptr_t base;
static int replace(uintptr_t *, uintptr_t, uintptr_t);
#include "timeline_reset.h"
static Read original;
static int matches, active, enabled, errors;
static uint64_t reads, s32_frames, converted_frames, samples, non_s32_frames, inexact_samples;
static int64_t first_pts, last_pts;
static double max_roundtrip_error;
typedef struct {
    int kind, flags, switch_serial, frames;
    int64_t pts, serial, queue_serial;
} Header;
static Header tail[3];
static unsigned tail_count;
static void *previous_frame, *previous_context;
static Header previous_header;
static int trim_eof;
static uint64_t duplicate_eof_frames;
static uint64_t side_data_frames, side_data_records, formats[16];
static int max_channels;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static int locate(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    (void)data;
    const char *n = strrchr(info->dlpi_name, '/');
    n = n ? n + 1 : info->dlpi_name;
    if (!strcmp(n, "libijk.so")) {
        base = info->dlpi_addr;
        matches++;
    }
    return 0;
}
static void retain_frame(void *frame) {
    uintptr_t v = FIELD(frame, 0, uintptr_t);
    ptrdiff_t offset = *(ptrdiff_t *)(v - 24);
    ((Ref)(base + 0x1f3848))((char *)frame + offset, frame);
}
static void release_frame(void *frame) {
    uintptr_t v = FIELD(frame, 0, uintptr_t);
    ptrdiff_t offset = *(ptrdiff_t *)(v - 24);
    ((Ref)(base + 0x1f38dc))((char *)frame + offset, frame);
}
static void *buffer_data(void *frame, size_t bytes) {
    void *b = FIELD(frame, 0x70, void *);
    if (!b)
        return NULL;
    size_t cap = FIELD(b, 0x18, size_t), off = FIELD(b, 0x20, size_t),
           valid = FIELD(b, 0x28, size_t);
    void *ptr = FIELD(b, 0x10, void *);
    if (!ptr || off > cap || valid > cap - off || bytes > valid)
        return NULL;
    return (char *)ptr + off;
}
static int copy_side_data(void *fresh, void *old) {
    uintptr_t begin = FIELD(old, 0xb8, uintptr_t), end = FIELD(old, 0xc0, uintptr_t),
              cap = FIELD(old, 0xc8, uintptr_t);
    if (begin > end || end > cap || (end - begin) % 56 || (end - begin) / 56 > 4096)
        return -1;
    size_t count = (end - begin) / 56;
    if (count)
        ((void (*)(void *, uintptr_t, uintptr_t, size_t))(base + 0x1a9ea0))((char *)fresh + 0xb8,
                                                                            begin, end, count);
    return (FIELD(fresh, 0xc0, uintptr_t) - FIELD(fresh, 0xb8, uintptr_t)) / 56 == count
               ? (int)count
               : -1;
}
#include "loudnorm_graph.h"
static uint64_t filter_total_ns, filter_max_ns, filter_over_40ms;
static uint64_t monotonic_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}
#include "slot_pair.h"
#include "clock_adapter.h"
#include "output_observer.h"
static int last_rate, last_channels;
static int (*receive_original)(void *, AVFrame *);
static void (*flush_original)(void *);
static int filter_tid, source_tid;
static int decoded_audio_codec;
static const char *decoded_audio_name;
static int codec_bypasses_loudness(int codec) {
    // Official 9.14 audio_thread supplies no custom af for these Dolby codecs.
    return codec == AV_CODEC_ID_AC3 || codec == AV_CODEC_ID_EAC3 || codec == AV_CODEC_ID_AC4;
}
static int receive_adapted(void *context, AVFrame *frame) {
    int rc = receive_original(context, frame);
    if (rc < 0 || frame->sample_rate <= 0 || frame->nb_samples <= 0)
        return rc;
    // Preloader lookups do not identify the stream that is producing audio frames.
    const AVCodec *codec = ((AVCodecContext *)context)->codec;
    __atomic_store_n(&decoded_audio_name, codec ? codec->name : NULL, __ATOMIC_RELEASE);
    __atomic_store_n(&decoded_audio_codec, ((AVCodecContext *)context)->codec_id, __ATOMIC_RELEASE);
    return rc;
}
static void flush_adapted(void *context) {
    flush_original(context);
    pthread_mutex_lock(&lp_lock);
    lp_reset();
    lp_flushes++;
    pthread_mutex_unlock(&lp_lock);
}
#include "decoder_reset.h"
static int replace_decoder(int install) {
    uintptr_t old[2] = {(uintptr_t)receive_original, (uintptr_t)flush_original};
    uintptr_t next[2] = {(uintptr_t)receive_adapted, (uintptr_t)flush_adapted};
    return patch_pair((uintptr_t *)(base + 0x2ca640), (uintptr_t *)(base + 0x2ca830),
                      install ? old : next, install ? next : old, mprotect);
}
static void *filter_source_frame(void *old, void *context) {
    AVFrame *frame = lp_frame_alloc();
    void *stream = NULL, *fresh = NULL;
    int rc = AVERROR(EINVAL), count = FIELD(old, 0xc, int), channels = FIELD(old, 0x60, int),
        rate = FIELD(old, 0x5c, int);
    uint64_t layout = FIELD(old, 0x68, uint64_t);
    if (!frame || count <= 0 || count > 262144 || channels <= 0 || channels > 8 || rate <= 0 ||
        rate > 384000 || !layout || FIELD(old, 0x80, uintptr_t) || FIELD(old, 0xd0, uintptr_t) ||
        FIELD(old, 0x100, uintptr_t))
        goto fail;
    rc = ((int (*)(void **, void *, AVFrame *))(base + 0x1a9a68))(&stream, old, frame);
    if (rc)
        goto fail;
    uint64_t av_layout = ((uint64_t (*)(uint64_t))(base + 0x1b2c30))(layout);
    if (!av_layout || frame->ch_layout.order != AV_CHANNEL_ORDER_NATIVE ||
        frame->ch_layout.nb_channels != channels || frame->sample_rate != rate ||
        frame->nb_samples != count) {
        rc = AVERROR(EINVAL);
        goto fail;
    }
    frame->ch_layout.u.mask = av_layout;
    rc = lp_apply(frame, context);
    if (rc)
        goto fail;
    graph_make_frame(&fresh, 4, layout, rate, count, base + 0x191e18);
    if (!fresh) {
        rc = AVERROR(ENOMEM);
        goto fail;
    }
    size_t bytes = (size_t)count * (size_t)channels * sizeof(float);
    void *pcm = buffer_data(fresh, bytes);
    if (!pcm || frame->linesize[0] < (int)(bytes * 2) || copy_side_data(fresh, old) < 0) {
        rc = AVERROR(EINVAL);
        goto fail;
    }
    // app.in multiplies double samples, then the output graph converts to float.
    const double *input = (const double *)frame->data[0];
    float *output = pcm;
    for (size_t i = 0; i < bytes / sizeof(float); i++)
        output[i] = (float)(input[i] * lp_gain);
    memcpy((char *)fresh + 8, (char *)old + 8, 0x68);
    FIELD(fresh, 0x4c, int) = 4;
    FIELD(FIELD(fresh, 0x70, void *), 0x28, size_t) = bytes;
    lp_frame_free(&frame);
    return fresh;
fail:
    lp_error = rc < 0 ? rc : AVERROR(EINVAL);
    if (fresh)
        release_frame(fresh);
    lp_frame_free(&frame);
    return NULL;
}
static SourceResult adapted(void *context) {
    // EOF/accurate-seek completion can leave the cached buffering flag set after the last packet hook.
    void *listener = FIELD(context, 0x30, void *);
    if (listener && FIELD(FIELD(listener, 0, uintptr_t), 0x70, uintptr_t) == base + 0x13b168) {
        void *checker = FIELD(listener, 0x10, void *);
        if (checker && FIELD(FIELD(checker, 0, uintptr_t), 0x70, uintptr_t) == base + 0x139d80 &&
            ((int (*)(void *))(base + 0x139d80))(checker))
            ((int (*)(void *))(base + 0x139d88))(checker);
    }
    int64_t previous_pts = FIELD(context, 0xb0, int64_t);
    SourceResult result = original(context);
    if (result.status == 0 && result.frame)
        notify_backward_pts(context, previous_pts, FIELD(result.frame, 0x18, int64_t));
    pthread_mutex_lock(&lp_lock);
    pthread_mutex_lock(&lock);
    reads++;
    source_tid = gettid();
    void *old = result.frame;
    int reset_error = __atomic_load_n(&decoder_reset_error, __ATOMIC_RELAXED);
    if (!reset_error)
        reset_error = __atomic_load_n(&timeline_reset_error, __ATOMIC_ACQUIRE);
    if (reset_error) {
        if (old)
            release_frame(old);
        result.frame = NULL;
        result.status = reset_error;
        goto done;
    }
again:
    if (result.status == 0 && old) {
        Header h = {FIELD(old, 8, int),   FIELD(old, 0x10, int),     FIELD(old, 0x14, int),
                    FIELD(old, 0xc, int), FIELD(old, 0x18, int64_t), result.serial,
                    result.queue_serial};
        if (tail_count < 3)
            tail[tail_count++] = h;
        else {
            tail[0] = tail[1];
            tail[1] = tail[2];
            tail[2] = h;
        }
        // The decoder enqueues its cached frame again on EOF; require the same retained object and ended queue.
        if (trim_eof && old == previous_frame && context == previous_context &&
            h.kind == previous_header.kind && h.flags == previous_header.flags &&
            h.switch_serial == previous_header.switch_serial &&
            h.frames == previous_header.frames && h.pts == previous_header.pts &&
            h.serial == previous_header.serial && h.queue_serial == previous_header.queue_serial &&
            ((int (*)(void *))(base + 0x15ae00))(context) == h.queue_serial &&
            ((int (*)(void *))(base + 0x15ae88))(context)) {
            release_frame(old);
            release_frame(previous_frame);
            previous_frame = NULL;
            duplicate_eof_frames++;
            result = original(context);
            reads++;
            old = result.frame;
            goto again;
        }
        if (trim_eof) {
            retain_frame(old);
            if (previous_frame)
                release_frame(previous_frame);
            previous_frame = old;
            previous_context = context;
            previous_header = h;
        }
        int format = FIELD(old, 0x4c, int), channels_seen = FIELD(old, 0x60, int);
        if (format >= 0 && format < 16)
            formats[format]++;
        if (channels_seen > max_channels)
            max_channels = channels_seen;
        uintptr_t side_begin = FIELD(old, 0xb8, uintptr_t), side_end = FIELD(old, 0xc0, uintptr_t);
        if (side_end > side_begin) {
            side_data_frames++;
            side_data_records += (side_end - side_begin) / 56;
        }
        last_rate = FIELD(old, 0x5c, int);
        last_channels = channels_seen;
        if (lp_profile &&
            !codec_bypasses_loudness(__atomic_load_n(&decoded_audio_codec, __ATOMIC_ACQUIRE))) {
            filter_tid = gettid();
            uint64_t begin = monotonic_ns();
            void *filtered = filter_source_frame(old, context);
            uint64_t elapsed = monotonic_ns() - begin;
            filter_total_ns += elapsed;
            if (elapsed > filter_max_ns)
                filter_max_ns = elapsed;
            if (elapsed > 40000000)
                filter_over_40ms++;
            if (!filtered) {
                errors++;
                release_frame(old);
                result.frame = NULL;
                result.status = lp_error;
                goto done;
            }
            result.frame = filtered;
            release_frame(old);
            goto done;
        }
        lp_bypassed++;
        if (format != 2) {
            non_s32_frames++;
            goto done;
        }
        s32_frames++;
        if (!enabled)
            goto done;
        int count = FIELD(old, 0xc, int), channels = FIELD(old, 0x60, int),
            rate = FIELD(old, 0x5c, int);
        uint64_t layout = FIELD(old, 0x68, uint64_t);
        if (count <= 0 || count > 262144 || channels <= 0 || channels > 8 || rate <= 0 ||
            rate > 384000 || !layout) {
            errors++;
            release_frame(old);
            result.frame = NULL;
            result.status = AVERROR(EINVAL);
            goto done;
        }
        size_t n = (size_t)count * (size_t)channels, bytes = n * 4;
        const int32_t *in = buffer_data(old, bytes);
        if (!in) {
            errors++;
            release_frame(old);
            result.frame = NULL;
            result.status = AVERROR(EINVAL);
            goto done;
        }
        void *fresh = NULL;
        graph_make_frame(&fresh, 4, layout, rate, count, base + 0x191e18);
        if (!fresh) {
            errors++;
            release_frame(old);
            result.frame = NULL;
            result.status = AVERROR(EINVAL);
            goto done;
        }
        float *out = buffer_data(fresh, bytes);
        if (!out || FIELD(fresh, 0x60, int) != channels) {
            release_frame(fresh);
            errors++;
            release_frame(old);
            result.frame = NULL;
            result.status = AVERROR(EINVAL);
            goto done;
        }
        // Keep original metadata ownership; hardware output-buffer callbacks stay with their original frame.
        if (FIELD(old, 0x80, uintptr_t) || FIELD(old, 0xd0, uintptr_t) ||
            FIELD(old, 0x100, uintptr_t) || copy_side_data(fresh, old) < 0) {
            release_frame(fresh);
            errors++;
            release_frame(old);
            result.frame = NULL;
            result.status = AVERROR(EINVAL);
            goto done;
        }
        memcpy((char *)fresh + 8, (char *)old + 8, 0x68);
        FIELD(fresh, 0x4c, int) = 4;
        for (size_t i = 0; i < n; i++) {
            out[i] = (float)in[i] * 0x1p-31f;
            double error = fabs((double)out[i] * 2147483648.0 - (double)in[i]);
            if (error != 0)
                inexact_samples++;
            if (error > max_roundtrip_error)
                max_roundtrip_error = error;
        }
        FIELD(FIELD(fresh, 0x70, void *), 0x28, size_t) = bytes;
        int64_t pts = FIELD(old, 0x18, int64_t);
        if (converted_frames == 0)
            first_pts = pts;
        last_pts = pts;
        converted_frames++;
        samples += count;
        result.frame = fresh;
        release_frame(old);
    }
done:

    pthread_mutex_unlock(&lock);
    pthread_mutex_unlock(&lp_lock);
    return result;
}
static int replace(uintptr_t *slot, uintptr_t expected, uintptr_t next) {
    if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) != expected)
        return -1;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0)
        return -1;
    void *address = (void *)((uintptr_t)slot & ~((uintptr_t)page - 1));
    if (mprotect(address, (size_t)page, PROT_READ | PROT_WRITE))
        return -1;
    __atomic_store_n(slot, next, __ATOMIC_RELEASE);
    if (!mprotect(address, (size_t)page, PROT_READ))
        return 0;
    __atomic_store_n(slot, expected, __ATOMIC_RELEASE);
    if (mprotect(address, (size_t)page, PROT_READ))
        _exit(120);
    return -1;
}
static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}
static int replace_reset_flush(int install) {
    uintptr_t old = (uintptr_t)decoder_flush_original, next = (uintptr_t)reset_decoder;
    return replace((uintptr_t *)(base + 0x2bbe50), install ? old : next, install ? next : old);
}
static int replace_source(int install) {
    return replace((uintptr_t *)(base + 0x2b7788), (uintptr_t)(install ? original : adapted),
                   (uintptr_t)(install ? adapted : original));
}
static void (*soundtouch_constructor)(void *);
static int (*soundtouch_setting)(void *, int, int);
static int stretch_error;
static void soundtouch_adjusted(void *object) {
    soundtouch_constructor(object);
    // The official SDL factory uses a 16ms overlap; AudioGraph otherwise keeps the 8ms default.
    int ok = soundtouch_setting(object, 5, 16);
    if (!ok)
        __atomic_store_n(&stretch_error, 1, __ATOMIC_RELAXED);
    __android_log_print(ANDROID_LOG_INFO, "PiliStretch", "official overlap_ms=16 applied=%d", ok);
}
static int replace_stretcher(int install) {
    uintptr_t old = (uintptr_t)soundtouch_constructor, next = (uintptr_t)soundtouch_adjusted;
    return replace((uintptr_t *)(base + 0x2ca920), install ? old : next, install ? next : old);
}
#include "video_trace.h"
#include "audio_buffer.h"
static int (*const changes[])(int) = {
    replace_source,       replace_decoder,      replace_clock_timestamp, replace_video_trace,
    replace_stretcher,    replace_reset_flush,  replace_timeline_flush,  replace_timeline_reset,
    replace_clock_resume, replace_audio_buffer, replace_output_track,    replace_output_engine};
static int installed;
static void restore(JNIEnv *env) {
    while (installed) {
        int rc = changes[installed - 1](0);
        if (rc == -2 || (rc && changes[installed - 1](0)))
            (*env)->FatalError(env, "Audio adapter restoration failed");
        installed--;
    }
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_startGraphAdapter(
    JNIEnv *env, jclass cls, jboolean loudness_enabled, jdouble gain_db, jboolean diagnostics,
    jboolean prefill, jboolean video_diagnostics) {
    (*env)->GetJavaVM(env, &output_vm);
    (void)cls;
    if (active || installed) {
        fail(env, "Graph adapter already active");
        return;
    }
    if (!isfinite(gain_db) || gain_db < -99 || gain_db > 10) {
        fail(env, "Invalid metadata gain");
        return;
    }
    matches = 0;
    base = 0;
    dl_iterate_phdr(locate, NULL);
    if (matches != 1 || *(uint64_t *)(base + 0x159d70) != UINT64_C(0xa9026ffed101c3ff) ||
        *(uint64_t *)(base + 0x191e18) != UINT64_C(0xa9077bfdd10343ff)) {
        fail(env, "Original source/factory ABI mismatch");
        return;
    }
    if (!player_instructions_ready()) {
        fail(env, "Prepared player image unavailable");
        return;
    }
    if (*(uint64_t *)(base + 0x13b168) != UINT64_C(0xf9400008f9400800) ||
        *(uint64_t *)(base + 0x139d80) != UINT64_C(0xd65f03c039404000) ||
        *(uint64_t *)(base + 0x139d88) != UINT64_C(0xf9001bfed10243ff)) {
        fail(env, "Original buffering ABI mismatch");
        return;
    }
    if (*(uint64_t *)(base + 0x196854) != UINT64_C(0xa9014ff4f81e0ffe) ||
        *(ptrdiff_t *)(base + 0x2bbdf8) != 0x28 ||
        *(uintptr_t *)(base + 0x2bbe20) != base + 0x1a6808 ||
        *(uintptr_t *)(base + 0x2bbe28) != base + 0x1a6798) {
        fail(env, "Original decoder lifecycle ABI mismatch");
        return;
    }
    decoder_flush_original = (int (*)(void *))(base + 0x1a674c);
    decoder_open_original = (int (*)(void *, void **))(base + 0x1a6808);
    decoder_reset_error = 0;
    decoder_resets = 0;
    if (prefill) {
        fail(env, "Startup prefill requires a separate player image");
        return;
    }
    if (lp_open()) {
        fail(env, "Locked filter runtime unavailable");
        return;
    }
    receive_original = dlsym(lp_lib, "avcodec_receive_frame");
    flush_original = dlsym(lp_lib, "avcodec_flush_buffers");
    if (!receive_original || !flush_original) {
        lp_close();
        fail(env, "Decoder APIs unavailable");
        return;
    }
    void *stretch_lib = dlopen("libijkffext.so", RTLD_NOW | RTLD_NOLOAD);
    if (!stretch_lib) {
        lp_close();
        fail(env, "Original stretcher unavailable");
        return;
    }
    soundtouch_constructor = dlsym(stretch_lib, "_ZN10soundtouch10SoundTouchC1Ev");
    soundtouch_setting = dlsym(stretch_lib, "_ZN10soundtouch10SoundTouch10setSettingEii");
    dlclose(stretch_lib);
    if (!soundtouch_constructor || !soundtouch_setting) {
        lp_close();
        fail(env, "Original stretcher APIs unavailable");
        return;
    }
    stretch_error = 0;
    audio_buffer_adjustments = 0;
    decoded_audio_codec = AV_CODEC_ID_NONE;
    decoded_audio_name = NULL;
    lp_profile = loudness_enabled ? 1 : 0;
    lp_metadata_gain_db = gain_db;
    clock_diagnostics = diagnostics;
    video_trace_enabled = video_diagnostics;
    original = (Read)(base + 0x159d70);
    enabled = trim_eof = 1;
    errors = 0;
    filter_tid = source_tid = 0;
    filter_total_ns = filter_max_ns = filter_over_40ms = 0;
    duplicate_eof_frames = side_data_frames = side_data_records = 0;
    max_channels = last_rate = last_channels = 0;
    memset(formats, 0, sizeof(formats));
    previous_frame = previous_context = NULL;
    memset(&previous_header, 0, sizeof(previous_header));
    reads = s32_frames = converted_frames = samples = non_s32_frames = inexact_samples =
        zero_timestamp_rejections = 0;
    max_roundtrip_error = 0;
    first_pts = last_pts = 0;
    tail_count = 0;
    memset(tail, 0, sizeof(tail));
    while (installed < (int)(sizeof(changes) / sizeof(*changes))) {
        int rc = changes[installed](1);
        if (rc) {
            if (rc == -2)
                (*env)->FatalError(env, "Audio adapter page protection failed");
            restore(env);
            lp_close();
            fail(env, "Audio adapter installation failed");
            return;
        }
        installed++;
    }
    active = 1;
}
JNIEXPORT jstring JNICALL Java_dev_piliplus_dspbridge_DspBridge_getGraphState(JNIEnv *env,
                                                                              jclass cls) {
    (void)cls;
    pthread_mutex_lock(&lp_lock);
    pthread_mutex_lock(&lock);
    const char *codec = __atomic_load_n(&decoded_audio_name, __ATOMIC_ACQUIRE);
    char out[640];
    snprintf(
        out, sizeof(out),
        "{\"active\":%s,\"errors\":%d,\"filter_error\":%d,\"sample_rate\":%d,\"channels\":%d,\"filtered_frames\":%llu,\"converted_frames\":%llu,\"duplicate_eof_frames\":%llu,\"loudness_bypassed_for_codec\":%s,\"audio_codec\":\"%.64s\"}",
        active ? "true" : "false",
        errors + __atomic_load_n(&stretch_error, __ATOMIC_RELAXED) +
            (__atomic_load_n(&decoder_reset_error, __ATOMIC_RELAXED) != 0) +
            (__atomic_load_n(&timeline_reset_error, __ATOMIC_ACQUIRE) != 0),
        lp_error, last_rate, last_channels, (unsigned long long)lp_frames,
        (unsigned long long)converted_frames, (unsigned long long)duplicate_eof_frames,
        lp_profile &&
                codec_bypasses_loudness(__atomic_load_n(&decoded_audio_codec, __ATOMIC_ACQUIRE))
            ? "true"
            : "false",
        codec ? codec : "");
    pthread_mutex_unlock(&lock);
    pthread_mutex_unlock(&lp_lock);
    return (*env)->NewStringUTF(env, out);
}
JNIEXPORT jstring JNICALL Java_dev_piliplus_dspbridge_DspBridge_finishGraphAdapter(JNIEnv *env,
                                                                                   jclass cls) {
    (void)cls;
    if (!active) {
        fail(env, "Graph adapter not active");
        return NULL;
    }
    restore(env);
    pthread_mutex_lock(&lp_lock);
    pthread_mutex_lock(&lock);
    active = 0;
    lp_close();
    if (previous_frame) {
        release_frame(previous_frame);
        previous_frame = NULL;
    }
    char out[512];
    snprintf(
        out, sizeof(out),
        "{\"restored\":true,\"video_trace_enabled\":%s,\"errors\":%d,\"filter_error\":%d,\"filtered_frames\":%llu,\"converted_frames\":%llu,\"filtered_samples\":%llu,\"duplicate_eof_frames\":%llu}",
        video_trace_enabled ? "true" : "false",
        errors + __atomic_load_n(&stretch_error, __ATOMIC_RELAXED) +
            (__atomic_load_n(&decoder_reset_error, __ATOMIC_RELAXED) != 0) +
            (__atomic_load_n(&timeline_reset_error, __ATOMIC_ACQUIRE) != 0),
        lp_error, (unsigned long long)lp_frames, (unsigned long long)converted_frames,
        (unsigned long long)lp_samples, (unsigned long long)duplicate_eof_frames);
    pthread_mutex_unlock(&lock);
    pthread_mutex_unlock(&lp_lock);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jint JNICALL Java_dev_piliplus_dspbridge_DspBridge_finishVideoTrace(JNIEnv *env,
                                                                              jclass cls,
                                                                              jstring path) {
    (void)cls;
    if (active || installed || !video_trace_enabled || video_overflow) {
        fail(env, "Video trace incomplete or still active");
        return -1;
    }
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name)
        return -1;
    FILE *out = fopen(name, "w");
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (!out) {
        fail(env, "Cannot create video trace");
        return -1;
    }
    int rc = dump_video_trace(out), closed = fclose(out);
    if (rc || closed) {
        fail(env, "Cannot save video trace");
        return -1;
    }
    return (jint)video_count;
}

#include "preload_interrupt_guards.h"
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_interruptPreload(JNIEnv *env,
                                                                              jclass cls,
                                                                              jlong handle) {
    (void)cls;
    void *lib = dlopen("libijk.so", RTLD_NOW | RTLD_LOCAL);
    void *symbol = lib ? dlsym(lib, "JNI_OnLoad") : NULL;
    Dl_info info;
    if (getuid() % 100000 < 10000 || !symbol || !handle || !dladdr(symbol, &info))
        goto fail;
    uintptr_t preload_base = (uintptr_t)info.dli_fbase;
    if (memcmp((void *)(preload_base + 0x1604fc), preload_getter_bytes,
               sizeof preload_getter_bytes) ||
        memcmp((void *)(preload_base + 0x193034), preload_interrupt_bytes,
               sizeof preload_interrupt_bytes) ||
        memcmp((void *)(preload_base + 0x1f3848), preload_retain_bytes,
               sizeof preload_retain_bytes) ||
        memcmp((void *)(preload_base + 0x1f38dc), preload_release_bytes,
               sizeof preload_release_bytes))
        goto fail;
    void *item = *(void **)(uintptr_t)handle;
    if (!item)
        goto fail;
    void **(*getDirector)(void *) = (void *)(preload_base + 0x1604fc);
    void (*retain)(void *, void *) = (void *)(preload_base + 0x1f3848);
    void (*release)(void *, void *) = (void *)(preload_base + 0x1f38dc);
    void (*interrupt)(void *, unsigned char) = (void *)(preload_base + 0x193034);
    void *director = *getDirector(item);
    if (!director)
        goto fail;
    // Match the original read looper's ownership around its IO-interrupt call.
    retain(director, &director);
    interrupt(director, 1);
    release(director, &director);
    dlclose(lib);
    return;
fail:
    if (lib)
        dlclose(lib);
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                     "Original preloader interrupt runtime mismatch");
}
