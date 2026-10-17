#include <pthread.h>
#include <stddef.h>
#include <libavcodec/codec_internal.h>

_Static_assert(offsetof(AVCodecContext, priv_data) == 0x20, "Locked codec context layout changed");
_Static_assert(sizeof(FFCodec) == 192 && offsetof(FFCodec, init) == 0x80 &&
                   offsetof(FFCodec, cb) == 0x88 && offsetof(FFCodec, flush) == 0x98,
               "Locked Dolby descriptor layout changed");
static FFCodec endpoint_codecs[2];
static int (*dolby_init_original)(AVCodecContext *);
static int (*dolby_decode_original)(AVCodecContext *, AVFrame *, int *, AVPacket *);
static pthread_mutex_t dolby_init_lock = PTHREAD_MUTEX_INITIALIZER;
static int32_t exec_params[9];
static atomic_int requested_endpoint, acknowledged_endpoint;
static atomic_uint endpoint_updates;

static int32_t *decoder_params(AVCodecContext *ctx) {
    return (int32_t *)((char *)ctx->priv_data + 0x28);
}
static int endpoint_init(AVCodecContext *ctx) {
    // The original init acknowledges its borrowed template; serialize that write, then isolate it.
    pthread_mutex_lock(&dolby_init_lock);
    int rc = dolby_init_original(ctx);
    if (rc >= 0) {
        int32_t *params = decoder_params(ctx);
        memcpy(params, exec_params, sizeof(exec_params));
        *(int32_t **)((char *)ctx->priv_data + 0x18) = params;
    }
    pthread_mutex_unlock(&dolby_init_lock);
    return rc;
}
static void request_decoder_endpoint(int32_t params[9], int endpoint) {
    if (params[0] != endpoint) {
        params[0] = endpoint;
        params[6] = (int32_t)((uint32_t)params[6] + 1u);
    }
}
static int endpoint_decode(AVCodecContext *ctx, AVFrame *frame, int *got, AVPacket *packet) {
    int32_t *params = decoder_params(ctx);
    int pending = params[6] != params[7];
    request_decoder_endpoint(params, atomic_load(&requested_endpoint));
    pending |= params[6] != params[7];
    int rc = dolby_decode_original(ctx, frame, got, packet);
    if (params[6] == params[7]) {
        atomic_store(&acknowledged_endpoint, params[0]);
        if (pending)
            atomic_fetch_add(&endpoint_updates, 1);
    }
    return rc;
}
static int prepare_endpoint_codecs(const void *eac3, const void *ac3) {
    const FFCodec *a = eac3, *b = ac3;
    if (a->priv_data_size != 0x28 || b->priv_data_size != 0x28 ||
        a->cb_type != FF_CODEC_CB_TYPE_DECODE || b->cb_type != FF_CODEC_CB_TYPE_DECODE ||
        !a->init || !a->cb.decode || a->init != b->init || a->cb.decode != b->cb.decode ||
        a->flush != b->flush || a->close != b->close)
        return -1;
    dolby_init_original = a->init;
    dolby_decode_original = a->cb.decode;
    endpoint_codecs[0] = *a;
    endpoint_codecs[1] = *b;
    for (int i = 0; i < 2; i++) {
        endpoint_codecs[i].priv_data_size = 0x28 + sizeof(exec_params);
        endpoint_codecs[i].init = endpoint_init;
        endpoint_codecs[i].cb.decode = endpoint_decode;
    }
    return 0;
}
