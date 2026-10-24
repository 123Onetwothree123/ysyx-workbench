#include <stdio.h>
#include <unistd.h>

/*
 * PA4.4 抢占式分时的第二个用户进程。
 *
 * 这个程序从不调用 yield()，只在自己的地址空间里做无休止的循环；
 * 如果日志中它和 hello 交替出现，说明 CPU 是在任意指令边界被时钟
 * 中断抢占的，而不是靠用户进程协作让出 CPU。
 *
 * 每 10000 次循环打印一行，既能在 NEMU 的软件模拟速度下及时看到
 * 输出，也不会让 NPC 的日志被刷屏。
 */
int main(void) {
  volatile unsigned long spin = 0;
  unsigned long round = 1;
  for (;;) {
    spin++;
    if (spin == 10000) {
      printf("spin[%d]: round %lu, still running without yield\n",
             getpid(), round);
      round++;
      spin = 0;
    }
  }
  return 0;
}
