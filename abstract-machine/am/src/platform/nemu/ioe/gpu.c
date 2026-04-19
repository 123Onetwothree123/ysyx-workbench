#include <am.h>
#include <nemu.h>

#define SYNC_ADDR (VGACTL_ADDR + 4)

static int screen_w = 0;
static int screen_h = 0;

void __am_gpu_init() {
  int i;
  AM_GPU_CONFIG_T cfg = io_read(AM_GPU_CONFIG);
  screen_w = cfg.width;
  screen_h = cfg.height;
  uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
  for (i = 0; i < screen_w * screen_h; i ++) fb[i] = i;
  outl(SYNC_ADDR, 1);
}

void __am_gpu_config(AM_GPU_CONFIG_T *cfg) {
  uint32_t info = inl(VGACTL_ADDR);
  screen_w = info >> 16;
  screen_h = info & 0xffff;
  *cfg = (AM_GPU_CONFIG_T) {
    .present = true, .has_accel = false,
    .width = screen_w, .height = screen_h,
    .vmemsz = screen_w * screen_h * sizeof(uint32_t)
  };
}

void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl) {
  if (ctl->pixels != NULL) {
    uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
    uint32_t *pixels = (uint32_t *)ctl->pixels;
    int x = ctl->x, y = ctl->y, dw = ctl->w, dh = ctl->h;
    for (int row = 0; row < dh; row++) {
      for (int col = 0; col < dw; col++) {
        fb[(y + row) * screen_w + (x + col)] = pixels[row * dw + col];
      }
    }
  }
  if (ctl->sync) {
    outl(SYNC_ADDR, 1);
  }
}

void __am_gpu_status(AM_GPU_STATUS_T *status) {
  status->ready = true;
}
