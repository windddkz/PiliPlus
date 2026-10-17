#ifndef OWNED_INSTRUCTION_PATCH_H
#define OWNED_INSTRUCTION_PATCH_H
#include <stddef.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>
// Only before player creation or after release: this page must not be executing.
static int patch_instructions(uint32_t *slot, const uint32_t *expected, const uint32_t *next,
                              size_t count, int (*protect)(void *, size_t, int)) {
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || ((uintptr_t)slot % 4) || count < 1 || count > 2)
        return -1;
    uintptr_t mask = (uintptr_t)page - 1, address = (uintptr_t)slot & ~mask;
    if (((uintptr_t)(slot + count - 1) & ~mask) != address)
        return -1;
    for (size_t i = 0; i < count; i++)
        if (slot[i] != expected[i])
            return -1;
    if (protect((void *)address, (size_t)page, PROT_READ | PROT_WRITE))
        return -1;
    for (size_t i = 0; i < count; i++)
        slot[i] = next[i];
    __builtin___clear_cache((char *)slot, (char *)(slot + count));
    if (!protect((void *)address, (size_t)page, PROT_READ | PROT_EXEC))
        return 0;
    for (size_t i = 0; i < count; i++)
        slot[i] = expected[i];
    __builtin___clear_cache((char *)slot, (char *)(slot + count));
    return protect((void *)address, (size_t)page, PROT_READ | PROT_EXEC) ? -2 : -1;
}
static int patch_instruction(uint32_t *slot, uint32_t expected, uint32_t next,
                             int (*protect)(void *, size_t, int)) {
    return patch_instructions(slot, &expected, &next, 1, protect);
}
#endif
