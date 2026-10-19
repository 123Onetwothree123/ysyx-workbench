#include <unistd.h>
#include <stdio.h>

int sched_yield(void);

int main() {
  write(1, "Hello World!\n", 13);
  int i = 2;
  volatile int j = 0;
  while (1) {
    j ++;
    if (j == 10000) {
      printf("Hello World from Navy-apps for the %dth time!\n", i ++);
      // 主动让出 CPU，让仙剑进程也能继续运行。
      sched_yield();
      j = 0;
    }
  }
  return 0;
}
