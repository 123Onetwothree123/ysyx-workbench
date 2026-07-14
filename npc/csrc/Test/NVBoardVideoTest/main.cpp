#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"GPU Test\n"}; *p; ++p) putch(*p);

    unsigned int Row[80];
    for (int Y = 0; Y < 480; ++Y)
    {
        int Band = Y / 60;
        unsigned int C;
        switch (Band)
        {
        case 0: C = 0x00FF0000U; break;
        case 1: C = 0x00FF7F00U; break;
        case 2: C = 0x00FFFF00U; break;
        case 3: C = 0x0000FF00U; break;
        case 4: C = 0x000000FFU; break;
        case 5: C = 0x004B0082U; break;
        case 6: C = 0x00FF00FFU; break;
        default: C = 0x00FFFFFFU; break;
        }
        for (int X = 0; X < 80; ++X) Row[X] = C;
        io_write(AM_GPU_FBDRAW, 0, Y, Row, 80, 1, false);
    }

    for (const char *p{"GPU Done\n"}; *p; ++p) putch(*p);
    while (1) {}
    return 0;
}
