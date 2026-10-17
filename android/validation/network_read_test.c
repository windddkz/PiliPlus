#define NETWORK_READ_TEST
#include "../official_audio/network_read.c"
#include <assert.h>
#include <pthread.h>
#include <stdio.h>

static _Thread_local unsigned sleeps, reads;
static int context;
static int fake_sleep(useconds_t delay) {
    assert(delay == 1 || delay == 1000);
    sleeps++;
    errno = EINTR;
    return -1;
}
static int fake_read(void *ctx, unsigned char *buffer, int size) {
    assert(ctx == &context && size == 8 && network_read_depth == 1 && errno == EBUSY);
    reads++;
    assert(network_sleep_detail(1000, 0, network_sdl + 0x1c7154) == 0 && errno == EBUSY);
    assert(network_sleep_detail(1000, 2, network_sdl + 0x1c7154) == -1 && errno == EINTR);
    buffer[0] = 42;
    errno = EAGAIN;
    return -11;
}
static void *worker(void *unused) {
    (void)unused;
    assert(!network_read_depth);
    unsigned char buffer[8];
    for (unsigned i = 0; i < 1000; i++) {
        errno = EBUSY;
        assert(network_read(&context, buffer, 8) == -11 && errno == EAGAIN && buffer[0] == 42);
        assert(!network_read_depth);
    }
    assert(reads == 1000 && sleeps == 1000);
    return NULL;
}
static unsigned protections, fail_at;
static int protect(void *page, size_t size, int flags) {
    if (++protections == fail_at) {
        errno = EACCES;
        return -1;
    }
    return mprotect(page, size, flags);
}
int main(void) {
    network_original_sleep = fake_sleep;
    network_original_read = fake_read;
    network_sdl = 0x10000000;
    errno = EBUSY;
    assert(network_sleep_detail(1000, 0, network_sdl + 0x1c7154) == -1 && errno == EINTR);
    network_read_depth = 1;
    errno = EBUSY;
    assert(network_sleep_detail(1000, 0, network_sdl + 0x1c7154) == 0 && errno == EBUSY);
    for (int error = 1; error <= 8; error++)
        assert(network_sleep_detail(1000, error, network_sdl + 0x1c7154) == -1);
    assert(network_sleep_detail(1000, 0, network_sdl + 0x1c70e0) == -1);
    assert(network_sleep_detail(1, 0, network_sdl + 0x1c7154) == -1);
    network_read_depth = 0;
    assert(atomic_load(&network_observed) == 1);
    pthread_t threads[8];
    for (unsigned i = 0; i < 8; i++)
        assert(!pthread_create(threads + i, NULL, worker, NULL));
    for (unsigned i = 0; i < 8; i++)
        assert(!pthread_join(threads[i], NULL));
    long page = sysconf(_SC_PAGESIZE);
    assert(page > 0);
    uintptr_t *slot =
        mmap(NULL, (size_t)page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(slot != MAP_FAILED);
    *slot = 11;
    assert(!mprotect(slot, (size_t)page, PROT_READ));
    assert(network_slot(slot, 12, 13, protect) == -1 && *slot == 11);
    assert(network_slot((void *)((char *)slot + 1), 11, 13, protect) == -1);
    protections = 0;
    fail_at = 1;
    assert(network_slot(slot, 11, 13, protect) == -1 && *slot == 11);
    protections = 0;
    fail_at = 2;
    assert(network_slot(slot, 11, 13, protect) == -1 && *slot == 11 && protections == 3);
    protections = 0;
    fail_at = 0;
    assert(!network_slot(slot, 11, 13, protect) && *slot == 13);
    assert(!network_slot(slot, 13, 11, protect) && *slot == 11);
    assert(!munmap(slot, (size_t)page));
    puts(
        "PASS: scoped success only; retry/write/non-scope preserved; return/errno/buffer; 8 threads x1000; slot mismatch/alignment/write failure/restore failure rollback");
}
