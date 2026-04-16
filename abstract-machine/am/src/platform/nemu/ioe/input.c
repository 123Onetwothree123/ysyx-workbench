#include <am.h>
#include <nemu.h>

#define KEYDOWN_MASK 0x8000

void __am_input_keybrd(AM_INPUT_KEYBRD_T *kbd)
{
  /*先标记一下，这是NEMU原有的代码，不要delete了
  kbd->keydown = 0;
  kbd->keycode = AM_KEY_NONE;
  */
  
}
