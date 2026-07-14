#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"GPU Test\n"}; *p; ++p) putch(*p);

    unsigned int Row[40];
    for (int Y = 0; Y < 10; ++Y)
    {
        unsigned int C;
        switch (Y)
        {
        case 0: C = 0x00FF0000U; break;
        case 1: C = 0x00FF7F00U; break;
        case 2: C = 0x00FFFF00U; break;
        case 3: C = 0x0000FF00U; break;
        case 4: C = 0x000000FFU; break;
        case 5: C = 0x004B0082U; break;
        case 6: C = 0x00FF00FFU; break;
        case 7: C = 0x0000FFFFU; break;
        case 8: C = 0x00FFFFFFU; break;
        default: C = 0x00808080U; break;
        }
        for (int X = 0; X < 40; ++X) Row[X] = C;
        io_write(AM_GPU_FBDRAW, 100, 200 + Y, Row, 40, 1, false);
    }

    for (const char *p{"GPU Done\n"}; *p; ++p) putch(*p);
    while (1) {}
    return 0;
}
