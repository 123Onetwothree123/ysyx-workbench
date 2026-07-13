#include <am.h>
int main(const char *) {
    volatile unsigned int *FB = (volatile unsigned int *)0x21000000U;
    for (int y = 0; y < 480; ++y)
        for (int x = 0; x < 640; ++x)
            FB[y * 640 + x] = 0x00FF0000U;
    while (1) {}
    return 0;
}
