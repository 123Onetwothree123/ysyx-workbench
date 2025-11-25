#include <stdio.h>
#include <nvboard.h>
#include <Vtop.h>
#include <memory>
#include <iostream>
void nvboard_bind_all_pins(Vtop *top);
class SimContext
{
private:
  std::unique_ptr<Vtop> dut;
  void single_cycle()
  {
    dut->clk = 0;
    dut->eval();
    dut->clk = 1;
    dut->eval();
  }

public:
  SimContext() : dut(std::make_unique<Vtop>())
  {
    nvboard_bind_all_pins(dut.get());
    nvboard_init();
    std::cout << "SimContext Initialized." << std::endl;
  }
  ~SimContext()
  {
    nvboard_quit();
    std::cout << "SimContext Destroyed." << std::endl;
  }
  void reset(int cycles = 10)
  {
    dut->rst = 1;
    for (int i = 0; i < cycles; ++i)
    {
      single_cycle();
    }
    dut->rst = 0;
  }
  void run()
  {
    while (true)
    {
      nvboard_update();
      single_cycle();
    }
  }
};
int main()
{
  printf("Hello, ysyx!\n");
  SimContext sim;
  sim.reset();
  sim.run();
  return 0;
}
