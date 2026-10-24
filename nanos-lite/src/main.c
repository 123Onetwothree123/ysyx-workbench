#include <common.h>

void init_mm(void);
void init_device(void);
void init_ramdisk(void);
void init_irq(void);
void init_fs(void);
void init_proc(void);

int main() {
  extern const char logo[];
  printf("%s", logo);
  Log("'Hello World!' from Nanos-lite");
  Log("Build time: %s, %s", __TIME__, __DATE__);

  init_mm();

  init_device();

  init_ramdisk();

#ifdef HAS_CTE
  init_irq();
  /*
   * 初始化期间关闭 S 模式中断。RV64GC 需要装入三个 ELF 用户程序，
   * 耗时可能超过一个 10ms 时间片；若 STIP 在 Context 尚未建立时
   * 到达，schedule() 找不到可运行 PCB，只能 halt。用户上下文第一次
   * sret 时会按保存的 SPIE 重新开中断，因此不影响后续抢占。
   */
  iset(false);
#endif

  init_fs();

  init_proc();

  Log("Finish initialization");

#ifdef HAS_CTE
  yield();
#endif

  panic("Should not reach here");
}
