#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

static int (*adaptive_original)(void *, void *);
static void *(*adaptive_reset)(void **, void *);

static int adaptive_flush(void *decoder, void *parameters) {
    int result = adaptive_original(decoder, parameters);
    if (result) return result;
    void **owner = (void **)((char *)decoder + 0x30);
    void *filter = *owner;
    if (filter) {
        void **table = *(void ***)filter;
        unsigned (*valid)(void *) = (void *)table[0x50 / sizeof(void *)];
        if (!(valid(filter) & 1)) {
            // Match AndroidMediaCodec::Open: unsupported/no-op filters must not receive packets.
            int (*close_filter)(void *) = (void *)table[0x38 / sizeof(void *)];
            close_filter(filter);
            adaptive_reset(owner, NULL);
        }
    }
    return result;
}

#ifndef PILIPLUS_ADAPTIVE_TEST
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include "media_adaptive_guards.h"

static int install_media_adaptive(void) {
    static int installed;
    if (installed) return -1;
    void *lib = dlopen("libijk.so", RTLD_NOW | RTLD_LOCAL);
    void *entry = lib ? dlsym(lib, "JNI_OnLoad") : NULL;
    Dl_info info = {0};
    if (!entry || !dladdr(entry, &info)) goto fail;
    uintptr_t base = (uintptr_t)info.dli_fbase;
    for (unsigned i = 0; i < sizeof adaptive_guards / sizeof adaptive_guards[0]; ++i)
        if (memcmp((void *)(base + adaptive_guards[i].offset), adaptive_guards[i].bytes,
                   adaptive_guards[i].size)) goto fail;
    uintptr_t *slot = (void *)(base + 0x2bd268);
    if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) != base + 0x1c399c ||
        *(uintptr_t *)(base + 0x2bd4b8) != base + 0x1c6850 ||
        *(uintptr_t *)(base + 0x2bd4d0) != base + 0x1c6b28) goto fail;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) goto fail;
    void *address = (void *)((uintptr_t)slot & ~((uintptr_t)page - 1));
    adaptive_original = (void *)(base + 0x1c399c);
    adaptive_reset = (void *)(base + 0x121bec);
    if (mprotect(address, (size_t)page, PROT_READ | PROT_WRITE)) goto fail;
    __atomic_store_n(slot, (uintptr_t)adaptive_flush, __ATOMIC_RELEASE);
    if (mprotect(address, (size_t)page, PROT_READ)) {
        __atomic_store_n(slot, base + 0x1c399c, __ATOMIC_RELEASE);
        if (mprotect(address, (size_t)page, PROT_READ)) abort();
        goto fail;
    }
    // Preloads and decoders retain this vtable until the player process exits.
    installed = 1;
    __android_log_print(ANDROID_LOG_INFO, "PiliAdaptive", "installed guarded filter validity check");
    return 0;
fail:
    if (lib) dlclose(lib);
    return -1;
}

JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installMediaAdaptive(JNIEnv *env, jclass cls) {
    (void)cls;
    if (getuid() % 100000 < 10000 || install_media_adaptive())
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                        "Original adaptive decoder runtime mismatch");
}
#endif
