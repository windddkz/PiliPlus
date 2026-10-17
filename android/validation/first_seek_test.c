#define FIRST_SEEK_TEST
#include "../official_audio/first_seek.c"
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>
static const char *dash = "ijkdash", *mov = "mov";
struct Context {
    void *klass;
    const char **format;
};
static struct Context contexts[70];
static unsigned char has_drm(void *);
static void *asset_vtable[3] = {NULL, NULL, (void *)has_drm};
static struct {
    void **vtable;
    int scheme, format, drm;
} asset = {asset_vtable, 1, 1, 0};
static unsigned char has_drm(void *p) {
    assert(p == &asset);
    return (unsigned char)asset.drm;
}
static void *opened;
static int open_rc, open_calls, seek_calls, read_calls, close_calls;
static void *seen_context, *seen_packet;
static int seen_stream, seen_flags;
static int64_t seen_min, seen_ts, seen_max;
static void *get(const void *d, const char *key) {
    assert(!strcmp(key, "media-asset"));
    return (void *)d;
}
static int open_input(void **ctx, const char *url, void *format, void **options) {
    assert(!strcmp(url, "owned") && !format);
    if (options)
        assert(*options == &asset);
    open_calls++;
    *ctx = opened;
    return open_rc;
}
static void close_input(void **ctx) {
    close_calls++;
    if (ctx)
        *ctx = NULL;
}
static int read_input(void *ctx, void *packet) {
    read_calls++;
    seen_context = ctx;
    seen_packet = packet;
    return -11;
}
static int seek_input(void *ctx, int stream, int64_t min, int64_t ts, int64_t max, int flags) {
    seek_calls++;
    seen_context = ctx;
    seen_stream = stream;
    seen_min = min;
    seen_ts = ts;
    seen_max = max;
    seen_flags = flags;
    return -22;
}
static int seek0(void *ctx) { return first_seek(ctx, -1, INT64_MIN, 0, INT64_MAX, 0); }
static void fresh_open(void *ctx) {
    void *out = NULL, *opts = &asset;
    opened = ctx;
    assert(first_open(&out, "owned", NULL, &opts) == 0 && out == ctx && opts == &asset);
}
static atomic_int winners;
static void *race_take(void *ctx) {
    atomic_fetch_add(&winners, take_fresh(ctx));
    return NULL;
}
static void *independent(void *ctx) {
    for (int i = 0; i < 10000; i++) {
        assert(add_fresh(ctx));
        assert(take_fresh(ctx));
    }
    return NULL;
}
int main(void) {
    dict_pointer = get;
    original_open = open_input;
    original_close = close_input;
    original_read = read_input;
    original_seek = seek_input;
    for (unsigned i = 0; i < 70; i++)
        contexts[i].format = &dash;
    void *a = &contexts[0], *b = &contexts[1];
    assert(seek0(a) == -22);
    fresh_open(a);
    assert(seek0(a) == 0);
    assert(seek0(a) == -22);
    fresh_open(a);
    assert(first_seek(a, -1, -100, 5000000, 9000000, 0) == -22);
    assert(seen_context == a && seen_stream == -1 && seen_min == -100 && seen_ts == 5000000 &&
           seen_max == 9000000 && !seen_flags);
    assert(seek0(a) == -22);
    fresh_open(a);
    assert(first_read(a, b) == -11 && seen_context == a && seen_packet == b && read_calls == 1);
    assert(seek0(a) == -22);
    fresh_open(a);
    assert(first_seek(a, 3, -5, 0, 99, 4) == -22 && seen_stream == 3 && seen_min == -5 &&
           seen_max == 99 && seen_flags == 4);
    assert(seek0(a) == -22);
    fresh_open(a);
    assert(first_seek(a, -1, 1, 0, 99, 0) == -22);
    fresh_open(a);
    assert(first_seek(a, -1, -10, 0, -1, 0) == -22);
    fresh_open(a);
    fresh_open(b);
    assert(seek0(b) == 0);
    assert(seek0(a) == 0);
    fresh_open(a);
    void *out = a;
    first_close(&out);
    assert(!out && close_calls == 1 && seek0(a) == -22);
    fresh_open(a);
    assert(seek0(a) == 0);
    fresh_open(a);
    open_rc = -5;
    void *opts = &asset;
    opened = a;
    out = a;
    assert(first_open(&out, "owned", NULL, &opts) == -5);
    assert(seek0(a) == -22);
    open_rc = 0;
    contexts[0].format = &mov;
    fresh_open(a);
    assert(seek0(a) == -22);
    contexts[0].format = &dash;
    asset.scheme = 2;
    fresh_open(a);
    assert(seek0(a) == -22);
    asset.scheme = 1;
    asset.format = 2;
    fresh_open(a);
    assert(seek0(a) == -22);
    asset.format = 1;
    asset.drm = 1;
    fresh_open(a);
    assert(seek0(a) == -22);
    asset.drm = 0;
    asset.vtable = NULL;
    fresh_open(a);
    assert(seek0(a) == -22);
    asset.vtable = asset_vtable;
    out = NULL;
    opened = a;
    assert(first_open(&out, "owned", NULL, NULL) == 0);
    assert(seek0(a) == -22);

    for (unsigned i = 0; i < FIRST_SEEK_CAPACITY; i++)
        assert(add_fresh(&contexts[i]));
    assert(!add_fresh(&contexts[64]));
    assert(seek0(&contexts[64]) == -22);
    for (unsigned i = 0; i < FIRST_SEEK_CAPACITY; i++)
        assert(seek0(&contexts[i]) == 0);
    assert(add_fresh(a));
    assert(!add_fresh(a));
    assert(seek0(a) == -22);
    assert(!add_fresh(NULL) && !take_fresh(NULL));
    pthread_t threads[8];
    assert(add_fresh(a));
    for (int i = 0; i < 8; i++)
        assert(!pthread_create(&threads[i], NULL, race_take, a));
    for (int i = 0; i < 8; i++)
        assert(!pthread_join(threads[i], NULL));
    assert(atomic_load(&winners) == 1);
    for (int i = 0; i < 8; i++)
        assert(!pthread_create(&threads[i], NULL, independent, &contexts[i]));
    for (int i = 0; i < 8; i++)
        assert(!pthread_join(threads[i], NULL));
    for (unsigned i = 0; i < FIRST_SEEK_CAPACITY; i++)
        assert(!fresh_contexts[i]);
    printf(
        "PASS eligibility/arguments/errors/open/read/close/reuse/capacity/duplicate; 8-thread one-shot and 80000 independent lifecycles; original seeks=%d\n",
        seek_calls);
    return 0;
}
