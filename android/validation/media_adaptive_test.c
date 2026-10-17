#define PILIPLUS_ADAPTIVE_TEST
#include "../official_audio/media_adaptive.c"
#include <assert.h>
#include <string.h>
#include <stdio.h>

static unsigned validity, checks, closes, resets, calls;
static int return_code;
static void *expected_decoder, *expected_parameters, *expected_filter;
static int original(void *d, void *p) {
    assert(d == expected_decoder && p == expected_parameters);
    calls++;
    return return_code;
}
static unsigned valid(void *f) { assert(f == expected_filter); checks++; return validity; }
static int close_filter(void *f) { assert(f == expected_filter); closes++; return 0; }
static void *reset(void **owner, void *value) {
    assert(owner == (void **)((char *)expected_decoder + 0x30));
    assert(*owner == expected_filter && value == NULL && closes == 1);
    resets++;
    *owner = value;
    return owner;
}
int main(void) {
    adaptive_original = original;
    adaptive_reset = reset;
    void *table[11] = {0};
    table[7] = (void *)close_filter;
    table[10] = (void *)valid;
    void *filter = table;
    unsigned char decoder[0x50] __attribute__((aligned(16)));
    for (int outcome = 0; outcome < 3; outcome++)
        for (unsigned state = 0; state < 4; state++) {
            memset(decoder, 0xa5, sizeof decoder);
            expected_decoder = decoder;
            expected_parameters = &state;
            expected_filter = &filter;
            void **owner = (void **)(decoder + 0x30);
            *owner = state ? &filter : NULL;
            validity = state == 3 ? 3 : state == 2 ? 1 : 0;
            return_code = outcome == 0 ? 0 : outcome == 1 ? -11 : 0x98adf3;
            calls = checks = closes = resets = 0;
            assert(adaptive_flush(decoder, &state) == return_code);
            assert(calls == 1 && checks == (unsigned)(outcome == 0 && state != 0));
            unsigned released = outcome == 0 && state == 1;
            assert(closes == released && resets == released);
            assert(*owner == (released || state == 0 ? NULL : &filter));
            for (size_t i = 0; i < sizeof decoder; i++)
                if (i < 0x30 || i >= 0x38) assert(decoder[i] == 0xa5);
            if (released) {
                assert(adaptive_flush(decoder, &state) == 0);
                assert(closes == 1 && resets == 1 && checks == 1);
            }
        }
    puts("12 result/filter combinations, ownership and repeated cleanup passed");
}
