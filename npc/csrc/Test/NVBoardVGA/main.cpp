#include <am.h>
int main(const char *)
{
    volatile unsigned int *FB = (volatile unsigned int *)0x21000000U;

    for (int y = 0; y < 480; ++y)
    {
        unsigned int Color;
        if (y < 100)
            Color = 0x00FF0000U;
        else if (y < 200)
            Color = 0x0000FF00U;
        else if (y < 300)
            Color = 0x000000FFU;
        else if (y < 400)
            Color = 0x00FFFF00U;
        else
            Color = 0x00FF00FFU;

        FB[y * 640 + 10] = Color;
        FB[y * 640 + 629] = Color;
    }

    for (int x = 0; x < 640; ++x)
        FB[239 * 640 + x] = 0x00FFFFFFU;

    while (1) {}
    return 0;
}
