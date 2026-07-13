#include <am.h>
#include <string.h>

void __am_gpu_config(AM_GPU_CONFIG_T *cfg)
{
    *cfg = (AM_GPU_CONFIG_T){
        .present = true, .has_accel = false,
        .width = 640, .height = 480,
        .vmemsz = 0
    };
}

void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl)
{
    uint32_t *pixels = ctl->pixels;
    if (pixels)
    {
        uint32_t *FB = (uint32_t *)0x21000000ul;
        int X = ctl->x, Y = ctl->y, W = ctl->w, H = ctl->h;
        int Len = sizeof(uint32_t) * W;
        uint32_t *Ptr = &FB[Y * 640 + X];
        for (int j = 0; j < H; j++)
        {
            memcpy(Ptr, pixels, Len);
            Ptr += 640;
            pixels += W;
        }
    }
}
