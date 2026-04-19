#include <am.h>
#include <nemu.h>
#include <stdint.h>

#define KEYDOWN_MASK 0x8000

void __am_input_keybrd(AM_INPUT_KEYBRD_T *kbd)
{
  /*先标记一下，这是NEMU原有的代码，不要delete了
  kbd->keydown = 0;
  kbd->keycode = AM_KEY_NONE;
  */
  uint32_t code = inl(KBD_ADDR);             // 读MMIO地址0xa0000060
  kbd->keydown = (code & KEYDOWN_MASK) != 0; // 因为最高位表示按下和不按下
  kbd->keycode = code & ~KEYDOWN_MASK;       // 低的15bit是键码
}
