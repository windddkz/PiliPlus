#include <libavcodec/avcodec.h>

extern void graph_make_decoder(void **, uintptr_t);
static int (*decoder_flush_original)(void *);
static int (*decoder_open_original)(void *, void **);
static int decoder_reset_error;
static unsigned decoder_resets;

static int reset_decoder(void *decoder) {
    AVCodecContext *context = FIELD(decoder, 8, AVCodecContext *);
    if (!context || context->codec_id != AV_CODEC_ID_AAC ||
        (context->profile != AV_PROFILE_AAC_HE && context->profile != AV_PROFILE_AAC_HE_V2))
        return decoder_flush_original(decoder);
    void *fresh = NULL;
    int rc = AVERROR(EINVAL);
    if (!FIELD(decoder, 0x20, void *))
        goto fail;
    graph_make_decoder(&fresh, base + 0x196854);
    if (!fresh) {
        rc = AVERROR(ENOMEM);
        goto fail;
    }
    rc = decoder_open_original(fresh, (void **)((char *)decoder + 0x20));
    if (rc) {
        if (rc > 0)
            rc = AVERROR(EINVAL);
        goto fail;
    }
    AVCodecContext *next = FIELD(fresh, 8, AVCodecContext *);
    if (!next || !FIELD(fresh, 0x10, void *) || !FIELD(fresh, 0x18, void *) ||
        !FIELD(fresh, 0x20, void *) || next->codec_id != context->codec_id ||
        (next->sample_rate != context->sample_rate &&
         (int64_t)next->sample_rate * 2 != context->sample_rate) ||
        (next->ch_layout.nb_channels != context->ch_layout.nb_channels &&
         !(context->profile == AV_PROFILE_AAC_HE_V2 && next->ch_layout.nb_channels == 1 &&
           context->ch_layout.nb_channels == 2))) {
        rc = AVERROR(EINVAL);
        goto fail;
    }
    // Implicit SBR/PS may expose only the half-rate/mono core until the first packet is decoded.
    // Prepare first so a failed Open cannot destroy the live decoder; ordinary HE-AAC flush retains audio history.
    for (size_t offset = 8; offset <= 0x20; offset += 8) {
        void *old = FIELD(decoder, offset, void *);
        FIELD(decoder, offset, void *) = FIELD(fresh, offset, void *);
        FIELD(fresh, offset, void *) = old;
    }
    release_frame(fresh);
    pthread_mutex_lock(&lp_lock);
    lp_reset();
    lp_flushes++;
    pthread_mutex_unlock(&lp_lock);
    __atomic_add_fetch(&decoder_resets, 1, __ATOMIC_RELAXED);
    __android_log_print(ANDROID_LOG_INFO, "PiliDecoder", "HE-AAC decoder recreated for flush");
    return 0;
fail: {
    AVCodecContext *candidate = fresh ? FIELD(fresh, 8, AVCodecContext *) : NULL;
    __android_log_print(
        ANDROID_LOG_ERROR, "PiliDecoder",
        "reset validation rc=%d old=%d/%d/%d/%d new=%d/%d/%d/%d resources=%d%d%d%d", rc,
        context->codec_id, context->profile, context->sample_rate, context->ch_layout.nb_channels,
        candidate ? (int)candidate->codec_id : -1, candidate ? candidate->profile : -1,
        candidate ? candidate->sample_rate : -1, candidate ? candidate->ch_layout.nb_channels : -1,
        candidate != NULL, fresh && FIELD(fresh, 0x10, void *) != NULL,
        fresh && FIELD(fresh, 0x18, void *) != NULL, fresh && FIELD(fresh, 0x20, void *) != NULL);
}
    if (fresh)
        release_frame(fresh);
    // The native caller ignores Flush's return; stop source output and expose the failure for fallback.
    __atomic_store_n(&decoder_reset_error, rc, __ATOMIC_RELAXED);
    __android_log_print(ANDROID_LOG_ERROR, "PiliDecoder", "HE-AAC decoder reset failed: %d", rc);
    return rc;
}
