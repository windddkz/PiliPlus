#include <assert.h>
#include "../official_audio/media_dash.h"
struct Stream {
    int codec, id;
};
static int calls;
static int codec(uintptr_t p) { return ((struct Stream *)p)->codec; }
static void bind(int type, int target, void *out, uintptr_t *vector) {
    assert(type == 0 && target == 0);
    calls++;
    size_t n = (vector[1] - vector[0]) / sizeof(uintptr_t);
    *(int *)((char *)out + 4) = (int)n;
    for (size_t i = 0; i < n; i++) {
        struct Stream *s = (void *)((uintptr_t *)vector[0])[i];
        assert(s->codec == 2);
        *(uintptr_t *)((char *)out + 0x18 + i * 0x58) = (uintptr_t)s;
    }
}
int main(void) {
    _Alignas(16) unsigned char source[0x2968], before[0x2968];
    struct Stream streams[81];
    uintptr_t pointers[81];
    for (int i = 0; i < 81; i++) {
        streams[i] = (struct Stream){i % 4 == 2 ? 2 : 0, i};
        pointers[i] = (uintptr_t)&streams[i];
    }
    uintptr_t vector[3] = {(uintptr_t)pointers, (uintptr_t)(pointers + 80),
                           (uintptr_t)(pointers + 81)};
    memset(source, 0xa5, sizeof source);
    *(int *)(source + 12) = 0;
    memcpy(before, source, sizeof source);
    assert(dash_add_av1(source, vector, codec, bind) == 20 && calls == 1);
    assert(*(int *)(source + 12) == 20);
    for (int i = 0; i < 20; i++)
        assert(*(uintptr_t *)(source + 0xdd8 + i * 0x58) == pointers[i * 4 + 2]);
    memcpy(before + 12, source + 12, 4);
    memcpy(before + 0xdd8, source + 0xdd8, 20 * 0x58);
    assert(!memcmp(before, source, sizeof source));
    assert(dash_add_av1(source, vector, codec, bind) == -1 && calls == 1);
    *(int *)(source + 12) = 0;
    streams[0].codec = 2;
    assert(dash_add_av1(source, vector, codec, bind) == -1 && calls == 1);
    streams[0].codec = 0;
    vector[1] = (uintptr_t)(pointers + 81);
    assert(dash_add_av1(source, vector, codec, bind) == -1 && calls == 1);
    vector[1] = vector[0] + 1;
    assert(dash_add_av1(source, vector, codec, bind) == -1);
    vector[1] = vector[0] + sizeof(uintptr_t);
    memcpy(before, source, sizeof source);
    assert(dash_add_av1(source, vector, codec, bind) == 0 && calls == 1 &&
           !memcmp(before, source, sizeof source));
    pointers[0] = 0;
    assert(dash_add_av1(source, vector, codec, bind) == -1);
    vector[0] = vector[1] = vector[2] = 0;
    assert(dash_add_av1(source, vector, codec, bind) == 0);
    return 0;
}
