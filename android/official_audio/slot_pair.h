#ifndef OWNED_SLOT_PAIR_H
#define OWNED_SLOT_PAIR_H
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>
// Only while the owned player is absent or fully released; both slots share a RELRO page.
// -1: unchanged or rolled back; -2: protection cannot be restored, caller must terminate.
static int patch_pair(uintptr_t *a, uintptr_t *b, const uintptr_t expected[2],
                      const uintptr_t next[2], int (*protect)(void *, size_t, int)) {
    long size = sysconf(_SC_PAGESIZE);
    if (size <= 0 || a == b)
        return -1;
    uintptr_t mask = (uintptr_t)size - 1, base = (uintptr_t)a & ~mask;
    if (((uintptr_t)b & ~mask) != base || ((uintptr_t)a % sizeof(uintptr_t)) ||
        ((uintptr_t)b % sizeof(uintptr_t)))
        return -1;
    if (__atomic_load_n(a, __ATOMIC_ACQUIRE) != expected[0] ||
        __atomic_load_n(b, __ATOMIC_ACQUIRE) != expected[1])
        return -1;
    if (protect((void *)base, (size_t)size, PROT_READ | PROT_WRITE))
        return -1;
    __atomic_store_n(a, next[0], __ATOMIC_RELEASE);
    __atomic_store_n(b, next[1], __ATOMIC_RELEASE);
    if (!protect((void *)base, (size_t)size, PROT_READ))
        return 0;
    // A failed mprotect leaves the page writable; undo both writes before reporting failure.
    __atomic_store_n(a, expected[0], __ATOMIC_RELEASE);
    __atomic_store_n(b, expected[1], __ATOMIC_RELEASE);
    return protect((void *)base, (size_t)size, PROT_READ) ? -2 : -1;
}
#endif
