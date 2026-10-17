#include <assert.h>
#include <stdint.h>
#include <stdatomic.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <libavcodec/avcodec.h>
#include "../official_audio/dolby_endpoint.h"
static int failing;
static int init_mock(AVCodecContext *ctx) {
    *(int32_t **)((char *)ctx->priv_data + 0x18) = exec_params;
    exec_params[7] = exec_params[6];
    return failing ? -1 : 0;
}
static int decode_mock(AVCodecContext *ctx, AVFrame *frame, int *got, AVPacket *packet) {
    (void)frame;
    (void)got;
    (void)packet;
    int32_t *params = *(int32_t **)((char *)ctx->priv_data + 0x18);
    assert(params == decoder_params(ctx));
    assert(params[0] == 1 || params[0] == 2);
    if (!failing)
        params[7] = params[6];
    return 0;
}
static void *request_many(void *arg) {
    (void)arg;
    for (int i = 0; i < 100000; i++)
        atomic_store(&requested_endpoint, 1 + i % 2);
    return NULL;
}
int main(void) {
    FFCodec original = {.priv_data_size = 0x28,
                        .cb_type = FF_CODEC_CB_TYPE_DECODE,
                        .init = init_mock,
                        .cb.decode = decode_mock};
    FFCodec bad = original;
    bad.priv_data_size++;
    assert(prepare_endpoint_codecs(&bad, &original) < 0);
    assert(!prepare_endpoint_codecs(&original, &original));
    assert(endpoint_codecs[0].priv_data_size == 76);
    assert(original.init == init_mock && original.cb.decode == decode_mock);
    original.cb.decode = NULL;
    assert(endpoint_codecs[0].cb.decode == endpoint_decode);
    exec_params[0] = 1;
    exec_params[1] = 0;
    exec_params[3] = -14;
    atomic_store(&requested_endpoint, 1);
    AVCodecContext a = {0}, b = {0};
    a.priv_data = calloc(1, 76);
    b.priv_data = calloc(1, 76);
    assert(a.priv_data && b.priv_data);
    assert(!endpoint_init(&a) && !endpoint_init(&b));
    int32_t *pa = decoder_params(&a), *pb = decoder_params(&b);
    assert(pa != pb && pa[0] == 1 && pb[0] == 1);
    atomic_store(&requested_endpoint, 2);
    endpoint_decode(&a, NULL, NULL, NULL);
    assert(pa[0] == 2 && pa[6] == 1 && pa[7] == 1 && pb[0] == 1);
    endpoint_decode(&b, NULL, NULL, NULL);
    assert(pb[0] == 2 && pb[7] == 1);
    endpoint_decode(&a, NULL, NULL, NULL);
    assert(pa[6] == 1);
    atomic_store(&requested_endpoint, 1);
    failing = 1;
    endpoint_decode(&a, NULL, NULL, NULL);
    assert(pa[0] == 1 && pa[6] == 2 && pa[7] == 1);
    assert(atomic_load(&acknowledged_endpoint) == 2);
    failing = 0;
    endpoint_decode(&a, NULL, NULL, NULL);
    assert(pa[7] == 2 && atomic_load(&acknowledged_endpoint) == 1);
    pa[6] = pa[7] = -1;
    request_decoder_endpoint(pa, 2);
    assert(pa[6] == 0 && pa[7] == -1);
    pthread_t thread;
    assert(!pthread_create(&thread, NULL, request_many, NULL));
    for (int i = 0; i < 100000; i++)
        endpoint_decode(&a, NULL, NULL, NULL);
    assert(!pthread_join(thread, NULL));
    atomic_store(&requested_endpoint, 1);
    endpoint_decode(&a, NULL, NULL, NULL);
    assert(pa[0] == 1 && pa[6] == pa[7]);
    assert(exec_params[0] == 1 && exec_params[6] == 0);
    free(a.priv_data);
    free(b.priv_data);
    puts(
        "PASS: decoder ownership, update/ack/retry, version wrap, atomic request stress, descriptor ABI rejection");
}
