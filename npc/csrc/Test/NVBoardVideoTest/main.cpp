#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    AM_GPU_CONFIG_T Cfg = io_read(AM_GPU_CONFIG);
    int W = Cfg.width;
    int H = Cfg.height;
    for (const char *p{"GPU Test\n"}; *p; ++p) putch(*p);

    unsigned int ColorBuf[W * H];
    for (int i = 0; i < W * H; ++i)
    {
        int X = i % W;
        int Band = X / 100;
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
        ColorBuf[i] = C;
    }
    io_write(AM_GPU_FBDRAW, 0, 0, ColorBuf, W, H, true);

    for (const char *p{"GPU Done\n"}; *p; ++p) putch(*p);
    while (1) {}
    return 0;
}
