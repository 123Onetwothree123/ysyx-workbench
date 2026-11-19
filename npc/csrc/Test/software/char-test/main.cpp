// 字符输出裸机测试：启动后直接在 UART 上输出一个字符，验证 AM 启动路径
// 和串口 MMIO 写通路。链接地址/入口由 AM 的 npc 平台（0x80000000）提供。
#include <am.h>
#include <klib.h>

int main() {
  volatile char *serial_port = (volatile char *)0x10000000;
  *serial_port = 'A';
  return 0;
}
