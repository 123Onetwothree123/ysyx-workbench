#include <stdio.h>
#include <nvboard.h>
#include <Vtop.h>
void nvboard_bind_all_pins(Vtop *top);

int main()
{
  printf("Hello, ysyx!\n");
  Vtop dut;
  nvboard_bind_all_pins(&dut);
  nvboard_init();
  while (1)
  {
    dut.eval();
    nvboard_update();
  }
  nvboard_quit();
  return 0;
}
