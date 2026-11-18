// VGA功能测试: 往帧缓冲(0x21000000)写标准8色彩条,然后halt
// 仿真台用 --vga-check 采样 externalPins_vga_* 校验时序并导出 vga_frame.ppm
#include <am.h>
#include <klib.h>

int main() {
  volatile uint32_t *fb = reinterpret_cast<volatile uint32_t *>(0x21000000ul);
  const uint32_t bars[8] = {
    0xffffff, 0xffff00, 0x00ffff, 0x00ff00,
    0xff00ff, 0xff0000, 0x0000ff, 0x000000
  };
  for (int y = 0; y < 480; y++) {
    for (int x = 0; x < 640; x++) {
      fb[y * 640 + x] = bars[x / 80];
    }
  }
  printf("VGA test pattern written: 8 color bars, 640x480\n");
  return 0;
}
