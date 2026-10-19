#include <NDL.h>
#include <stdio.h>

int main() {
  NDL_Init(0);
  uint32_t last = 0;
  while (1) {
    uint32_t now = NDL_GetTicks();
    if (now - last >= 500) {
      printf("timer: %u ms\n", now);
      last = now;
    }
  }
  return 0;
}
