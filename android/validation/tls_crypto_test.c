#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <pthread.h>
typedef int (*setkey_fn)(void *, const unsigned char *, uint32_t);
typedef int (*gcm_fn)(void *, unsigned char *, const unsigned char *, uint32_t,
                      const unsigned char *, uint32_t, const unsigned char *, uint32_t,
                      const unsigned char *, uint32_t);
static setkey_fn setkey;
static gcm_fn original;
static void *(*ctx_new)(void);
static void (*ctx_free)(void *);
static const void *(*aes[3])(void);
static int (*enc_init)(void *, const void *, void *, const unsigned char *, const unsigned char *);
static int (*dec_init)(void *, const void *, void *, const unsigned char *, const unsigned char *);
static int (*enc_update)(void *, unsigned char *, int *, const unsigned char *, int);
static int (*dec_update)(void *, unsigned char *, int *, const unsigned char *, int);
static int (*enc_final)(void *, unsigned char *, int *);
static int (*dec_final)(void *, unsigned char *, int *);
static int (*ctrl)(void *, int, int, void *);
#define LOAD(dst, name)                                                                            \
    do {                                                                                           \
        *(void **)(&(dst)) = dlsym(crypto, (name));                                                \
        assert((dst) != NULL);                                                                     \
    } while (0)
static uint32_t rng = 0x304915U;
static void bytes(unsigned char *p, size_t n) {
    while (n--) {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        *p++ = (unsigned char)rng;
    }
}
static gcm_fn candidate;
static int evp(void *sdl, unsigned char *out, const unsigned char *in, uint32_t len,
               const unsigned char *iv, uint32_t ivlen, const unsigned char *tag, uint32_t taglen,
               const unsigned char *aad, uint32_t aadlen) {
    return candidate(sdl, out, in, len, iv, ivlen, tag, taglen, aad, aadlen);
}
static void encrypt(unsigned keylen, const unsigned char *key, const unsigned char *iv,
                    unsigned ivlen, const unsigned char *aad, unsigned aadlen,
                    const unsigned char *plain, unsigned len, unsigned char *cipher,
                    unsigned char *tag, unsigned taglen) {
    void *ctx = ctx_new();
    assert(ctx);
    int n = 0, tail = 0;
    assert(enc_init(ctx, aes[(keylen - 16) / 8](), NULL, NULL, NULL) == 1);
    assert(ctrl(ctx, 9, (int)ivlen, NULL) == 1);
    assert(enc_init(ctx, NULL, NULL, key, iv) == 1);
    if (aadlen)
        assert(enc_update(ctx, NULL, &n, aad, (int)aadlen) == 1);
    assert(enc_update(ctx, cipher, &n, plain, (int)len) == 1 && n == (int)len);
    assert(enc_final(ctx, cipher + n, &tail) == 1 && tail == 0);
    assert(ctrl(ctx, 0x10, (int)taglen, tag) == 1);
    ctx_free(ctx);
}
static unsigned tests = 0;
static void one(unsigned keylen, unsigned ivlen, unsigned aadlen, unsigned len, unsigned taglen,
                int in_place) {
    _Alignas(32) unsigned char ctx[4096] = {0}, snapshot[4096];
    unsigned char key[32], iv[32], aad[64], plain[16384], cipher[16416], tag[16], left[16416],
        right[16416], before[16416];
    bytes(key, keylen);
    bytes(iv, ivlen);
    bytes(aad, aadlen);
    bytes(plain, len);
    assert(setkey(ctx, key, keylen) == 0);
    memcpy(snapshot, ctx, sizeof(ctx));
    for (unsigned i = 0; i < keylen; i++)
        assert(ctx[i ^ 3] == key[i]);
    encrypt(keylen, key, iv, ivlen, aad, aadlen, plain, len, cipher, tag, taglen);
    for (int corrupt = 0; corrupt < 2; corrupt++) {
        memset(left, 0x5a, sizeof(left));
        if (in_place)
            memcpy(left, cipher, len);
        memcpy(right, left, sizeof(left));
        memcpy(before, left, sizeof(left));
        if (corrupt)
            tag[0] ^= 1;
        int a = original(ctx, left, in_place ? left : cipher, len, iv, ivlen, tag, taglen,
                         aadlen ? aad : NULL, aadlen);
        int b = evp(ctx, right, in_place ? right : cipher, len, iv, ivlen, tag, taglen,
                    aadlen ? aad : NULL, aadlen);
        if (a != b || a != (corrupt ? -180 : 0) || memcmp(left, right, sizeof(left)) ||
            memcmp(ctx, snapshot, sizeof(ctx))) {
            fprintf(stderr, "Mismatch key=%u iv=%u aad=%u len=%u tag=%u alias=%d bad=%d rc=%d/%d\n",
                    keylen, ivlen, aadlen, len, taglen, in_place, corrupt, a, b);
            abort();
        }
        if (corrupt)
            assert(memcmp(left, before, sizeof(left)) == 0);
        else
            assert(memcmp(left, plain, len) == 0);
        tests++;
    }
}
static uint64_t now(void) {
    struct timespec t;
    assert(clock_gettime(CLOCK_MONOTONIC, &t) == 0);
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static double bench(gcm_fn fn) {
    _Alignas(32) unsigned char ctx[4096] = {0};
    unsigned char key[16] = {0}, iv[12] = {0}, aad[13] = {0}, plain[16384] = {0}, cipher[16416],
                  tag[16], out[16384];
    assert(setkey(ctx, key, 16) == 0);
    encrypt(16, key, iv, 12, aad, 13, plain, sizeof(plain), cipher, tag, 16);
    uint64_t start = now();
    for (int i = 0; i < 128; i++) {
        assert(fn(ctx, out, cipher, 16384, iv, 12, tag, 16, aad, 13) == 0);
        assert(memcmp(out, plain, sizeof(plain)) == 0);
    }
    return (double)(now() - start) / 1000000;
}
static void (*alloc_fail)(int);
static unsigned (*call_count)(int);
static unsigned long (*peek_error)(void), (*get_error)(void);
static void (*clear_error)(void);
static void edges(void) {
    _Alignas(32) unsigned char ctx[4096] = {0}, saved[4096];
    unsigned char key[16] = {0}, iv[12] = {0}, aad[13] = {0}, plain[20480] = {0}, cipher[20512],
                  tag[16], a[20512], b[20512];
    assert(setkey(ctx, key, 16) == 0);
    memcpy(saved, ctx, sizeof ctx);
    const unsigned lens[] = {1, 16, 16384, 16385, 20480};
    for (unsigned l = 0; l < 5; l++) {
        unsigned n = lens[l];
        encrypt(16, key, iv, 12, aad, 13, plain, n, cipher, tag, 16);
        for (int mode = 0; mode < 5; mode++) {
            memset(a, 0xa5, sizeof a);
            memcpy(b, a, sizeof a);
            const unsigned char *ia = cipher, *ib = cipher;
            unsigned char *oa = a, *ob = b;
            if (mode == 1) {
                memcpy(a, cipher, n);
                memcpy(b, cipher, n);
                ia = a;
                ib = b;
                oa = a + 1;
                ob = b + 1;
            }
            if (mode == 2) {
                memcpy(a + 1, cipher, n);
                memcpy(b + 1, cipher, n);
                ia = a + 1;
                ib = b + 1;
            }
            if (mode == 3)
                alloc_fail(1);
            if (mode == 4)
                tag[0] ^= 1;
            errno = EDOM;
            int ra = original(ctx, oa, ia, n, iv, 12, tag, 16, aad, 13), ea = errno;
            errno = EDOM;
            int rb = candidate(ctx, ob, ib, n, iv, 12, tag, 16, aad, 13), eb = errno;
            assert(ra == rb && ea == eb && !memcmp(a, b, sizeof a) &&
                   !memcmp(ctx, saved, sizeof ctx));
            if (mode == 3)
                alloc_fail(0);
            if (mode == 4)
                tag[0] ^= 1;
            assert(peek_error() == 0);
        }
    }
    // These null inputs are rejected by the original wrapper before dereferencing them.
    for (int bad = 0; bad < 4; bad++) {
        void *c = bad == 0 ? NULL : ctx;
        unsigned char *oa = bad == 1 ? NULL : a, *ob = bad == 1 ? NULL : b;
        const unsigned char *in = bad == 2 ? NULL : cipher, *v = bad == 3 ? NULL : iv;
        memset(a, 0xa5, sizeof a);
        memcpy(b, a, sizeof a);
        int ra = original(c, oa, in, 16, v, 12, tag, 16, aad, 13),
            rb = candidate(c, ob, in, 16, v, 12, tag, 16, aad, 13);
        assert(ra < 0 && ra == rb && !memcmp(a, b, sizeof a));
    }
    encrypt(16, key, iv, 12, aad, 13, plain, 16, cipher, tag, 16);
    void *ev = ctx_new();
    assert(ev);
    assert(dec_init(ev, aes[0](), NULL, key, iv) == 1);
    assert(ctrl(ev, 0x7fffffff, 0, NULL) == 0);
    unsigned long pending = peek_error();
    assert(pending);
    unsigned before = call_count(1);
    assert(candidate(ctx, b, cipher, 16, iv, 12, tag, 16, aad, 13) == 0);
    assert(!memcmp(b, plain, 16) && peek_error() == pending && call_count(1) == before);
    assert(get_error() == pending && get_error() == 0);
    ctx_free(ev);
    clear_error();
}
static _Alignas(32) unsigned char shared_ctx[4096];
static unsigned char shared_cipher[16416], shared_tag[16];
static void *worker(void *arg) {
    (void)arg;
    unsigned char out[16384], iv[12] = {0}, aad[13] = {0}, tag[16];
    memcpy(tag, shared_tag, 16);
    for (unsigned i = 0; i < 100; i++) {
        errno = EDOM;
        assert(candidate(shared_ctx, out, shared_cipher, sizeof out, iv, 12, tag, 16, aad, 13) ==
                   0 &&
               errno == EDOM);
        for (unsigned j = 0; j < sizeof out; j++)
            assert(out[j] == 0);
        if (i % 10 == 0) {
            tag[0] ^= 1;
            memset(out, 0xa5, sizeof out);
            assert(candidate(shared_ctx, out, shared_cipher, sizeof out, iv, 12, tag, 16, aad,
                             13) == -180);
            tag[0] ^= 1;
            for (unsigned j = 0; j < sizeof out; j++)
                assert(out[j] == 0xa5);
        }
        assert(peek_error() == 0);
    }
    return NULL;
}
static void concurrent(void) {
    unsigned char key[16] = {0}, iv[12] = {0}, aad[13] = {0}, plain[16384] = {0}, saved[4096];
    assert(setkey(shared_ctx, key, 16) == 0);
    memcpy(saved, shared_ctx, sizeof saved);
    encrypt(16, key, iv, 12, aad, 13, plain, sizeof plain, shared_cipher, shared_tag, 16);
    pthread_t threads[4];
    for (unsigned i = 0; i < 4; i++)
        assert(!pthread_create(&threads[i], NULL, worker, NULL));
    for (unsigned i = 0; i < 4; i++)
        assert(!pthread_join(threads[i], NULL));
    assert(!memcmp(shared_ctx, saved, sizeof saved));
}
static void normal_ssl(uintptr_t base) {
    void *ssl = dlopen("libssl.so", RTLD_NOW | RTLD_LOCAL);
    assert(ssl);
    const void *(*method)(void) = (void *)dlsym(ssl, "TLS_method");
    void *(*newctx)(const void *) = (void *)dlsym(ssl, "SSL_CTX_new");
    void *(*newssl)(void *) = (void *)dlsym(ssl, "SSL_new");
    void (*connect_state)(void *) = (void *)dlsym(ssl, "SSL_set_connect_state");
    void (*free_ssl)(void *) = (void *)dlsym(ssl, "SSL_free");
    void (*free_ctx)(void *) = (void *)dlsym(ssl, "SSL_CTX_free");
    int (*read)(void *, void *, int) = (void *)dlsym(ssl, "SSL_read"),
                                (*via_plt)(void *, void *, int) = (void *)(base + 0x282e30);
    assert(method && newctx && newssl && connect_state && free_ssl && free_ctx && read);
    void *ctx = newctx(method());
    assert(ctx);
    void *a = newssl(ctx), *b = newssl(ctx);
    assert(a && b);
    connect_state(a);
    connect_state(b);
    unsigned char oa[16], ob[16];
    memset(oa, 0xa5, 16);
    memcpy(ob, oa, 16);
    clear_error();
    errno = EDOM;
    int ra = read(a, oa, 16), ea = errno;
    unsigned long err = get_error();
    assert(get_error() == 0);
    errno = EDOM;
    int rb = via_plt(b, ob, 16), eb = errno;
    assert(ra == rb && ea == eb && get_error() == err && get_error() == 0 && !memcmp(oa, ob, 16));
    free_ssl(a);
    free_ssl(b);
    free_ctx(ctx);
    dlclose(ssl);
    clear_error();
}
int main(int argc, char **argv) {
    assert(argc == 4);
    void *crypto = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
    assert(crypto);
    void *sdl = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!sdl) {
        fprintf(stderr, "%s\n", dlerror());
        return 2;
    }
    Dl_info info;
    void *sym = dlsym(sdl, "BilinetApi_Read");
    assert(sym && dladdr(sym, &info));
    uintptr_t base = (uintptr_t)info.dli_fbase;
    setkey = (setkey_fn)(base + 0x1d54b8);
    original = (gcm_fn)(base + 0x1d664c);
    LOAD(ctx_new, "EVP_CIPHER_CTX_new");
    LOAD(ctx_free, "EVP_CIPHER_CTX_free");
    LOAD(aes[0], "EVP_aes_128_gcm");
    LOAD(aes[1], "EVP_aes_192_gcm");
    LOAD(aes[2], "EVP_aes_256_gcm");
    LOAD(enc_init, "EVP_EncryptInit_ex");
    LOAD(dec_init, "EVP_DecryptInit_ex");
    LOAD(enc_update, "EVP_EncryptUpdate");
    LOAD(dec_update, "EVP_DecryptUpdate");
    LOAD(enc_final, "EVP_EncryptFinal_ex");
    LOAD(dec_final, "EVP_DecryptFinal_ex");
    LOAD(ctrl, "EVP_CIPHER_CTX_ctrl");
    void *adapter = dlopen(argv[3], RTLD_NOW | RTLD_LOCAL);
    if (!adapter) {
        fprintf(stderr, "%s\n", dlerror());
        return 3;
    }
    int (*install)(void) = (void *)dlsym(adapter, "tls_candidate_install");
    assert(install && install() == 0);
    assert(install() != 0);
    candidate = original;
    original = (gcm_fn)dlsym(adapter, "tls_candidate_original");
    assert(original);
    unsigned (*count)(int) = (void *)dlsym(adapter, "tls_candidate_count");
    assert(count);
    call_count = count;
    alloc_fail = (void *)dlsym(adapter, "tls_candidate_alloc_fail");
    assert(alloc_fail);
    LOAD(peek_error, "ERR_peek_error");
    LOAD(get_error, "ERR_get_error");
    LOAD(clear_error, "ERR_clear_error");
    normal_ssl(base);
    edges();
    concurrent();
    const unsigned keys[] = {16, 24, 32}, ivs[] = {1, 8, 12, 16}, aads[] = {0, 1, 13, 16, 17, 32},
                   lens[] = {0, 1, 15, 16, 17, 31, 1024, 16384}, tags[] = {1, 4, 8, 12, 16};
    for (unsigned k = 0; k < 3; k++)
        for (unsigned v = 0; v < 4; v++)
            for (unsigned a = 0; a < 6; a++)
                for (unsigned n = 0; n < 8; n++)
                    for (unsigned t = 0; t < 5; t++)
                        for (int ip = 0; ip < 2; ip++)
                            one(keys[k], ivs[v], aads[a], lens[n], tags[t], ip);
    printf(
        "{\"cases\":%u,\"key_recovery\":true,\"outputs_contexts_and_errors_equal\":true,\"bench_bytes\":2097152,\"bench\":[",
        tests);
    fflush(stdout);
    for (int i = 0; i < 3; i++) {
        double old = bench(original), fast = bench(evp);
        printf("%s{\"original_sdl_ms\":%.6f,\"official_evp_ms\":%.6f}", i ? "," : "", old, fast);
        fflush(stdout);
    }
    printf("],\"normal_ssl_plt_edges_concurrent\":true,\"fast_calls\":%u,\"fallback_calls\":%u}\n",
           count(1), count(0));
    assert(count(1) > 100 && count(0) > 100);
    return 0;
}
