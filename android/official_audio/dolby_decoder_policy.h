#include <stdint.h>

// The locked engine otherwise bypasses the registered Dolby decoder and its endpoint controls.
static int dolby_software_policy(int32_t *caps, int32_t saved[2], int install) {
    int32_t current[2];
    for (int i = 0; i < 2; i++) {
        current[i] = __atomic_load_n(caps + i, __ATOMIC_ACQUIRE);
        if (install ? (current[i] < -1 || current[i] > 1) : current[i] != 0)
            return -1;
    }
    for (int i = 0; i < 2; i++) {
        if (install)
            saved[i] = current[i];
        __atomic_store_n(caps + i, install ? 0 : saved[i], __ATOMIC_RELEASE);
    }
    return 0;
}
