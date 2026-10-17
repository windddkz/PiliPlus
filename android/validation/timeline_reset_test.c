#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#define FIELD(p, o, T) (*(T *)((char *)(p) + (o)))
#define AVERROR(e) (-(e))
#define ANDROID_LOG_INFO 1
#define ANDROID_LOG_ERROR 2
#define __android_log_print(...) ((void)0)
static uintptr_t base;
static int replace(uintptr_t *slot, uintptr_t expected, uintptr_t next) {
    (void)slot;
    (void)expected;
    (void)next;
    return 0;
}
#include "../official_audio/timeline_reset.h"
static _Alignas(16) unsigned char renderer[0x140], engine[0x120];
static uintptr_t table[20];
static _Alignas(16) unsigned char source[0x180];
static uintptr_t notification[7], callback;
static int notifications;
static int64_t notified;
static void on_pts(void *p, const int64_t *pts) {
    assert(p == &callback);
    notifications++;
    notified = *pts;
}
static char order[12];
static int count, pause_error, play_error, flush_error;
static int pause_engine(void *p) {
    assert(p == engine);
    order[count++] = 'p';
    if (!pause_error)
        FIELD(p, 0xb8, int) = 3;
    return pause_error;
}
static int play_engine(void *p) {
    assert(p == engine);
    order[count++] = 's';
    if (!play_error)
        FIELD(p, 0xb8, int) = 2;
    return play_error;
}
static int flush_engine(void *p, int64_t serial) {
    assert(p == engine && serial == 7);
    order[count++] = 'f';
    FIELD(p, 0xb8, int) = 1;
    return flush_error;
}
static void reset_engine(void *p) {
    assert(p == renderer);
    order[count++] = 'r';
    if ((FIELD(p, 0xe8, unsigned char) | FIELD(p, 0xc8, unsigned char)) & 1) {
        assert(FIELD(engine, 0xb8, int) != 2);
        FIELD(p, 0xe8, unsigned char) = FIELD(p, 0xc8, unsigned char) = 0;
        int rc = flush_timeline_checked(engine, 7);
        if (!rc)
            order[count++] = 'b';
    }
}
static void setup(int playing, int pending) {
    memset(renderer, 0, sizeof renderer);
    memset(engine, 0, sizeof engine);
    memset(order, 0, sizeof order);
    count = pause_error = play_error = flush_error = timeline_reset_error = 0;
    FIELD(renderer, 0x68, void *) = engine;
    FIELD(renderer, 0x10, unsigned char) = 1;
    FIELD(renderer, 0xe8, unsigned char) = pending;
    FIELD(engine, 0, void *) = table;
    FIELD(engine, 0xb8, int) = playing ? 2 : 3;
    table[0x28 / 8] = (uintptr_t)play_engine;
    table[0x30 / 8] = (uintptr_t)pause_engine;
    timeline_reset_original = reset_engine;
    timeline_flush_original = flush_engine;
}
int main(void) {
    (void)replace_timeline_reset;
    (void)replace_timeline_flush;
    setup(1, 1);
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "prfbs") && FIELD(engine, 0xb8, int) == 2);
    setup(0, 1);
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "rfb") && FIELD(engine, 0xb8, int) == 1);
    setup(1, 0);
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "r") && FIELD(engine, 0xb8, int) == 2);
    setup(1, 1);
    pause_error = -5;
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "p") && timeline_reset_error == -5);
    setup(1, 1);
    play_error = -6;
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "prfbs") && timeline_reset_error == -6);
    setup(1, 1);
    flush_error = -7;
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "prf") && timeline_reset_error == -7);
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "prf"));
    setup(0, 1);
    flush_error = 3;
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "rf") && timeline_reset_error == -EIO);
    setup(1, 0);
    FIELD(renderer, 0xc8, unsigned char) = 1;
    reset_timeline_quiesced(renderer);
    assert(!strcmp(order, "prfbs"));
    setup(0, 0);
    flush_error = -9;
    assert(flush_timeline_checked(engine, 7) == -9 && timeline_reset_error == -9);
    callback = (uintptr_t)notification;
    notification[6] = (uintptr_t)on_pts;
    FIELD(source, 0x170, void *) = &callback;
    notify_backward_pts(source, 1000000, 500000);
    notify_backward_pts(source, -1, 1000000);
    notify_backward_pts(source, 1000000, -1);
    notify_backward_pts(source, 0, 1000000);
    assert(!notifications);
    notify_backward_pts(source, 1000000, 499999);
    assert(notifications == 1 && notified == 499999);
    notify_backward_pts(source, INT64_MAX, 0);
    assert(notifications == 2 && notified == 0);
    FIELD(source, 0x170, void *) = NULL;
    notify_backward_pts(source, 1000000, 0);
    assert(notifications == 2);
    puts(
        "10 lifecycle/error and 7 backward-PTS cases passed; mock ABI, not hardware error injection");
}
