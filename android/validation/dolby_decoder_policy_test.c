#include <assert.h>
#include "../official_audio/dolby_decoder_policy.h"
int main(void) {
    int32_t saved[2], caps[2];
    for (int a = -1; a <= 1; a++)
        for (int b = -1; b <= 1; b++) {
            caps[0] = a;
            caps[1] = b;
            assert(!dolby_software_policy(caps, saved, 1));
            assert(caps[0] == 0 && caps[1] == 0);
            assert(!dolby_software_policy(caps, saved, 0));
            assert(caps[0] == a && caps[1] == b);
        }
    caps[0] = -1;
    caps[1] = 2;
    assert(dolby_software_policy(caps, saved, 1) == -1 && caps[0] == -1 && caps[1] == 2);
    caps[0] = 0;
    caps[1] = 1;
    assert(dolby_software_policy(caps, saved, 0) == -1 && caps[0] == 0 && caps[1] == 1);
    return 0;
}
