#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <sys/mman.h>
#include <android/log.h>
#include "media_cache.h"
#include "media_cache_guards.h"

static char cache_root[256];
static int (*cache_open_original)(void *, const char *, int, void **);
static void (*cache_destroy_original)(void *);
static void (*cache_destroy2_original)(void **);
static int (*cache_set)(void **, const char *, const char *, int);
struct CacheDictionaryEntry {
    char *key, *value;
};
static struct CacheDictionaryEntry *(*cache_get)(void *, const char *, const void *, int);

/* Accept only our hash directory and a positive decimal track ID. */
static int cache_path(const char *path, char *base, size_t size, int temporary) {
    if (!path || !cache_root[0])
        return 0;
    size_t prefix = strlen(cache_root);
    if (strncmp(path, cache_root, prefix) || path[prefix] != '/')
        return 0;
    const char *hash = path + prefix + 1;
    if (strlen(hash) < 67)
        return 0;
    for (int i = 0; i < 64; i++)
        if (!((hash[i] >= '0' && hash[i] <= '9') || (hash[i] >= 'a' && hash[i] <= 'f')))
            return 0;
    if (hash[64] != '/')
        return 0;
    const char *track = hash + 65;
    if (temporary && *track++ != '.')
        return 0;
    const char *end = track;
    while (*end >= '0' && *end <= '9')
        end++;
    if (end == track || end - track > 10)
        return 0;
    char number[11];
    memcpy(number, track, (size_t)(end - track));
    number[end - track] = 0;
    unsigned long id = strtoul(number, NULL, 10);
    if (!id || id > INT_MAX)
        return 0;
    if (temporary) {
        if (*end++ != '-')
            return 0;
        const char *stamp = end;
        while (*end >= '0' && *end <= '9')
            end++;
        if (end == stamp || end - stamp > 20 || strcmp(end, ".m4s.tmp"))
            return 0;
    } else if (strcmp(end, ".map"))
        return 0;
    int count = snprintf(base, size, "%s/%.*s/%lu", cache_root, 64, hash, id);
    return count > 0 && (size_t)count < size;
}
static int cached_open(void *owner, const char *url, int flags, void **options) {
    struct CacheDictionaryEntry *entry =
        options ? cache_get(*options, "cache_file_path", NULL, 1) : NULL;
    char base[384], data[400], map[400];
    if (!entry || !cache_path(entry->value, base, sizeof base, 1))
        return cache_open_original(owner, url, flags, options);
    snprintf(data, sizeof data, "%s.bin", base);
    snprintf(map, sizeof map, "%s.map", base);
    int restored = cache_index_valid(map, data);
    if (!restored) {
        if ((unlink(map) && errno != ENOENT) || (unlink(data) && errno != ENOENT))
            return cache_open_original(owner, url, flags, options);
    }
    if (cache_set(options, "cache_file_path", data, 0) < 0 ||
        cache_set(options, "cache_map_path", map, 0) < 0 ||
        cache_set(options, "cache_file_close", "0", 0) < 0 ||
        cache_set(options, "parse_cache_map", restored ? "1" : "0", 0) < 0 ||
        cache_set(options, "auto_save_map", "1", 0) < 0)
        return -ENOMEM;
    __android_log_print(ANDROID_LOG_INFO, "PiliMediaCache", "index_restore=%d", restored);
    return cache_open_original(owner, url, flags, options);
}
static void cached_destroy(void **handle) {
    char base[384], data[400], map[400], pending[416];
    if (!handle || !*handle || !cache_path((char *)*handle + 0x30, base, sizeof base, 0)) {
        cache_destroy2_original(handle);
        return;
    }
    snprintf(data, sizeof data, "%s.bin", base);
    snprintf(map, sizeof map, "%s.map", base);
    snprintf(pending, sizeof pending, "%s.next", map);
    // The locked manager reserves 512 bytes for this path; it is no longer read after close.
    strcpy((char *)*handle + 0x30, pending);
    cache_destroy_original(*handle);
    *handle = NULL;
    int saved = cache_index_valid(pending, data) && rename(pending, map) == 0;
    if (!saved) {
        unlink(pending);
        unlink(map);
        unlink(data);
    }
    __android_log_print(ANDROID_LOG_INFO, "PiliMediaCache", "index_saved=%d", saved);
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installMediaCache(JNIEnv *env,
                                                                               jclass cls,
                                                                               jstring directory) {
    (void)cls;
    if (!directory || cache_root[0] || getuid() % 100000 < 10000)
        goto fail;
    const char *root = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!root)
        return;
    size_t length = strlen(root);
    int valid = length > 0 && length < sizeof cache_root && root[0] == '/' &&
                root[length - 1] != '/' && !strstr(root, "/../") && !strstr(root, "/./");
    if (valid)
        memcpy(cache_root, root, length + 1);
    (*env)->ReleaseStringUTFChars(env, directory, root);
    if (!valid)
        goto fail;
    void *lib = dlopen("libijkffext.so", RTLD_NOW | RTLD_LOCAL);
    Dl_info info = {0};
    void *entry = lib ? dlsym(lib, "ijkio_manager_io_open") : NULL;
    if (!entry || !dladdr(entry, &info))
        goto fail;
    uintptr_t base = (uintptr_t)info.dli_fbase;
    for (unsigned i = 0; i < sizeof cache_guards / sizeof cache_guards[0]; i++)
        if (memcmp((void *)(base + cache_guards[i].offset), cache_guards[i].bytes,
                   cache_guards[i].length))
            goto fail;
    uintptr_t *open_slot = (void *)(base + 0x426568), *destroy_slot = (void *)(base + 0x426450);
    cache_set = dlsym(lib, "ijk_av_dict_set");
    cache_get = dlsym(lib, "ijk_av_dict_get");
    cache_destroy_original = dlsym(lib, "ijkio_manager_destroy");
    cache_destroy2_original = dlsym(lib, "ijkio_manager_destroyp2");
    if (!cache_set || !cache_get || (uintptr_t)entry != base + 0x1bcce0 ||
        *open_slot != (uintptr_t)entry || (uintptr_t)cache_destroy_original != base + 0x1bca94 ||
        (uintptr_t)cache_destroy2_original != base + 0x1bc9d0 ||
        *destroy_slot != (uintptr_t)cache_destroy2_original)
        goto fail;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0)
        goto fail;
    void *address = (void *)((uintptr_t)open_slot & ~((uintptr_t)page - 1));
    if ((uintptr_t)destroy_slot / (size_t)page != (uintptr_t)open_slot / (size_t)page ||
        mprotect(address, (size_t)page, PROT_READ | PROT_WRITE))
        goto fail;
    cache_open_original = entry;
    __atomic_store_n(open_slot, (uintptr_t)cached_open, __ATOMIC_RELEASE);
    __atomic_store_n(destroy_slot, (uintptr_t)cached_destroy, __ATOMIC_RELEASE);
    if (mprotect(address, (size_t)page, PROT_READ))
        abort();
    return;
fail:
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                     "Original media-cache runtime mismatch");
}
