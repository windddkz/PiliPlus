#include <assert.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include <sys/mman.h>
#define FIELD(p, o, T) (*(T *)((char *)(p) + (o)))
static uintptr_t base = 0x100000;
static uint64_t tick = 2000000000;
static uint64_t monotonic_ns(void) { return tick; }
static int patch_pair(uintptr_t *a, uintptr_t *b, const uintptr_t old[2], const uintptr_t next[2],
                      int (*protect)(void *, size_t, int)) {
    (void)a;
    (void)b;
    (void)old;
    (void)next;
    (void)protect;
    return 0;
}
#include "../official_audio/clock_resume.h"
static _Alignas(16) unsigned char engine[0xc0], sink[0x70], tracker[0x30],
    clock_state[0x80], other[0x80];
static int error;
static int play(void *e) {
    assert(e == engine);
    if (!error)
        FIELD(e, 0xb8, int) = 2;
    return error;
}
static int pause_engine(void *e) {
    assert(e == engine);
    if (!error)
        FIELD(e, 0xb8, int) = 3;
    return error;
}
static void setup(int paused) {
    clear_clock_resume();
    memset(engine, 0, sizeof engine);
    memset(clock_state, 0, sizeof clock_state);
    error = 0;
    clock_play_original = play;
    clock_pause_original = pause_engine;
    FIELD(engine, 0xb8, int) = paused ? 3 : 1;
    FIELD(engine, 0x88, void *) = sink;
    FIELD(sink, 0x60, void *) = tracker;
    FIELD(tracker, 0x20, void *) = clock_state;
    FIELD(clock_state, 0, uintptr_t) = base + 0x2b9b30;
    FIELD(clock_state, 0x28, int64_t) = 1000000;
    FIELD(clock_state, 0x60, int64_t) = 96000;
    FIELD(clock_state, 0x18, int64_t) = 500000;
    FIELD(clock_state, 0x39, unsigned char) = 1;
    FIELD(clock_state, 0x50, int64_t) = 96128;
}
int main(void) {
    (void)replace_clock_resume;
    setup(0);
    assert(clock_estimate_time(clock_state, 4000000) == 4000000);
    setup(0);
    assert(play_with_clock_resume(engine) == 0 && !clock_resume_pending);
    setup(1);
    error = -5;
    assert(play_with_clock_resume(engine) == -5 && !clock_resume_pending);
    setup(1);
    FIELD(clock_state, 0, uintptr_t) = 0;
    assert(play_with_clock_resume(engine) == 0 && !clock_resume_pending);
    setup(1);
    assert(play_with_clock_resume(engine) == 0 && clock_resume_pending == clock_state);
    assert(!resume_clock_timestamp(other, 1, 97000, 2100000, 2200000));
    assert(!resume_clock_timestamp(clock_state, 0, 97000, 2100000, 2200000));
    assert(!resume_clock_timestamp(clock_state, 1, 0, 2100000, 2200000));
    assert(!resume_clock_timestamp(clock_state, 1, 95000, 2100000, 2200000));
    assert(!resume_clock_timestamp(clock_state, 1, 97000, 1900000, 2200000));
    assert(!resume_clock_timestamp(clock_state, 1, 97000, 3200001, 2200000));
    assert(!resume_clock_timestamp(clock_state, 1, 97000, 2100000, 3200001));
    assert(clock_resume_pending == clock_state && FIELD(clock_state, 0x39, unsigned char) == 1);
    assert(resume_clock_timestamp(clock_state, 1, 97000, 2100000, 2200000));
    assert(!clock_resume_pending && !FIELD(clock_state, 0x39, unsigned char));
    assert(FIELD(clock_state, 0x50, int64_t) ==
           96128); // Preserve the original monotonic output bound.
    FIELD(clock_state, 0x39, unsigned char) = 1;
    assert(!resume_clock_timestamp(clock_state, 1, 98000, 2200000, 2300000));
    setup(1);
    play_with_clock_resume(engine);
    clear_clock_resume();
    assert(!clock_resume_pending);
    setup(0);
    FIELD(engine, 0xb8, int) = 2;
    error = -5;
    assert(pause_with_clock_resume(engine) == -5 && !clock_resume_pending);
    setup(0);
    assert(pause_with_clock_resume(engine) == 0 && !clock_resume_pending);
    setup(0);
    play_with_clock_resume(engine);
    assert(pause_with_clock_resume(engine) == 0 && clock_resume_paused);
    FIELD(clock_state, 0x48, int64_t) = 2000000;
    assert(clock_estimate_time(clock_state, 4000000) == 2000000);
    assert(clock_estimate_time(other, 4000000) == 4000000);
    FIELD(clock_state, 0x39, unsigned char) = 0;
    assert(clock_estimate_time(clock_state, 4000000) == 4000000);
    FIELD(clock_state, 0x39, unsigned char) = 1;
    assert(resume_clock_timestamp(clock_state, 1, 96000, 2100014, 2100000) == 2);
    FIELD(clock_state, 0x39, unsigned char) = 1;
    assert(resume_clock_timestamp(clock_state, 1, 96000, 2600011, 2600000) == 2);
    assert(clock_resume_pending == clock_state && FIELD(clock_state, 0x50, int64_t) == 96128);
    assert(!resume_clock_timestamp(clock_state, 1, 95000, 2600011, 2600000));
    error = -5;
    assert(play_with_clock_resume(engine) == -5 && clock_resume_paused);
    error = 0;
    tick = 3000000000;
    assert(play_with_clock_resume(engine) == 0 && !clock_resume_paused);
    FIELD(clock_state, 0x39, unsigned char) = 1;
    assert(clock_estimate_time(clock_state, 3400000) == 2000000);
    assert(!resume_clock_timestamp(clock_state, 1, 96000, 2600011, 3000000));
    assert(resume_clock_timestamp(clock_state, 1, 96000, 3100014, 3100000) == 2);
    FIELD(clock_state, 0x39, unsigned char) = 1;
    FIELD(clock_state, 0x58, int64_t) = 3100000;
    assert(clock_estimate_time(clock_state, 3200000) == 2000000 &&
           FIELD(clock_state, 0x58, int64_t) == 0);
    assert(resume_clock_timestamp(clock_state, 1, 96048, 3200014, 3200000) == 1);
    assert(clock_estimate_time(clock_state, 4000000) == 4000000);
    assert(!resume_clock_timestamp(clock_state, 1, 97000, 3200000, 3200000));
    assert(FIELD(clock_state, 0x50, int64_t) == 96128);
    pause_with_clock_resume(engine);
    clear_clock_resume();
    assert(!clock_resume_pending && !clock_resume_paused);
    setup(1);
    play_with_clock_resume(engine);
    FIELD(clock_state, 0x48, int64_t) = 2000000;
    FIELD(clock_state, 0x28, int64_t) = 5000000;
    assert(clock_estimate_time(clock_state, 3500000) == 2000000);
    assert(clock_estimate_time(clock_state, 3500001) == 3500001 && !clock_resume_pending &&
           !FIELD(clock_state, 0x39, unsigned char));
    setup(0);
    play_with_clock_resume(engine);
    pause_with_clock_resume(engine);
    FIELD(clock_state, 0x48, int64_t) = 2000000;
    assert(clock_estimate_time(clock_state, 33000000) == 2000000 && clock_resume_paused);
    // Native seek flush changes Paused to Prepared before Play reaches this wrapper.
    FIELD(engine, 0xb8, int) = 1;
    tick = 34000000000;
    assert(play_with_clock_resume(engine) == 0 && !clock_resume_paused);
    assert(clock_resume_pending == clock_state && clock_resume_since == 34000000);
    tick = 34001000000;
    assert(play_with_clock_resume(engine) == 0 && clock_resume_since == 34000000);
    assert(resume_clock_timestamp(clock_state, 1, 97000, 34010000, 34010000) == 1);
    assert(clock_estimate_time(clock_state, 34100000) == 34100000 && !clock_resume_pending);
    setup(0);
    play_with_clock_resume(engine);
    pause_with_clock_resume(engine);
    FIELD(engine, 0xb8, int) = 1;
    error = -5;
    assert(play_with_clock_resume(engine) == -5 && clock_resume_paused);
    error = 0;
    FIELD(tracker, 0x20, void *) = other;
    FIELD(other, 0, uintptr_t) = base + 0x2b9b30;
    assert(play_with_clock_resume(engine) == 0 && clock_resume_pending == clock_state &&
           clock_resume_paused);
    puts(
        "PASS: lifecycle success/failure, seek Prepared resume, repeated Play, clock ownership, paused sampling, timestamp rejection and preserved monotonic bound");
}
