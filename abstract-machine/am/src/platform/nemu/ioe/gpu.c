#include <am.h>
#include <nemu.h>
#include <stdint.h>

#define SYNC_ADDR (VGACTL_ADDR + 4)

void __am_gpu_init()
{
  /*
+  int i;
+  int w = 0;  // TODO: get the correct width
+  int h = 0;  // TODO: get the correct height
+  uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
+  for (i = 0; i < w * h; i ++) fb[i] = i;
+  outl(SYNC_ADDR, 1);
  */
  uint32_t Information = inl(VGACTL_ADDR);
  int i;
  int w = Information >> 16;    // TODO: get the correct width
  int h = Information & 0xffff; // TODO: get the correct height
  uint32_t *fb = (uint32_t *)(uintptr_t)FB_ADDR;
  for (i = 0; i < w * h; i++)
    fb[i] = i;
  outl(SYNC_ADDR, 1);
}

void __am_gpu_config(AM_GPU_CONFIG_T *cfg)
{
  /*
  *cfg = (AM_GPU_CONFIG_T) {
    .present = true, .has_accel = false,
    .width = 0, .height = 0,
    .vmemsz = 0
  };
  */
  uint32_t Information = inl(VGACTL_ADDR);
  uint32_t Width = Information >> 16;
  uint32_t Height = Information & 0xffff;
  *cfg = (AM_GPU_CONFIG_T){
      .present = true, .has_accel = false, .width = Width, .height = Height, .vmemsz = Width * Height * sizeof(uint32_t)};
}

void __am_gpu_fbdraw(AM_GPU_FBDRAW_T *ctl)
{
  /*
  if (ctl->sync)
  {
    outl(SYNC_ADDR, 1);
  }
  */
  int32_t x = ctl->x;
  int32_t y = ctl->y;
  int32_t w = ctl->w;
  int32_t h = ctl->h;
  uint32_t *Pixels = ctl->pixels;
  uint32_t ScreenWidth = inl(VGACTL_ADDR) >> 16;
  uint32_t *FrameBuffer = (uint32_t *)(uintptr_t)FB_ADDR;
  int32_t RowOffset = 0;    // 行
  int32_t ColumnOffset = 0; // 列
  for (RowOffset = 0; RowOffset < h; RowOffset++)
  {
    for (ColumnOffset = 0; ColumnOffset < w; ColumnOffset++)
    {
      /*
      牛逼，他妈的研究了半天都没搞懂pixels一个一维数组如何表示，想起来了以前看过的C++ primer plus想起来了可以一行过去，定义从
      哪里到哪里是一行，再从哪里到哪里是下一行，然后我又去问了下ai确认下能否这么设计，看起来是可以的，牛逼卧槽
      */
      FrameBuffer[(y + RowOffset) * ScreenWidth + (x + ColumnOffset)] = Pixels[RowOffset * w + ColumnOffset];
    }
  }
  if (ctl->sync)
  {
    outl(SYNC_ADDR, 1);
  }
}

void __am_gpu_status(AM_GPU_STATUS_T *status)
{
  status->ready = true;
}
