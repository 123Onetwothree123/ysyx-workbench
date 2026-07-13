#include <am.h>
int main(const char *) {
    volatile unsigned int *FB = (volatile unsigned int *)0x21000000U;
    for (int x = 0; x < 640; ++x)
        FB[x] = 0x0000FF00U;
    for (int x = 0; x < 640; ++x)
        FB[100 * 640 + x] = 0x00FF0000U;
    while (1) {}
    return 0;
}
