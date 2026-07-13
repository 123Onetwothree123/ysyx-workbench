#include <am.h>
#include<stdbool.h>

void __am_input_keybrd(AM_INPUT_KEYBRD_T *kbd) {
  kbd->keydown = false;
  kbd->keycode = AM_KEY_NONE;
}