#include <common.h>
#include <proc.h>
#include "syscall.h"

void do_syscall(Context *c);

// 真实发生的时钟中断次数，用于验证抢占频率和时间片数量。
static unsigned long timer_irq_count = 0;

static Context *do_event(Event e, Context *c)
{
  switch (e.event)
  {
  case EVENT_YIELD:
    return schedule(c);
  case EVENT_IRQ_TIMER:
    // 时钟中断不依赖用户进程主动 yield：AM 把 S 模式 STIP 打包成
    // EVENT_IRQ_TIMER 后，这里直接轮转 PCB。
    timer_irq_count ++;
    Log("[preempt] timer irq #%lu: pid %d -> schedule",
        timer_irq_count, current == NULL ? -1 : current->pid);
    return schedule(c);
  case EVENT_SYSCALL:
    // 用户进程调用 yield 时直接调度，避免在内核态再次陷入造成嵌套上下文。
    if ((int)c->GPR1 == SYS_yield) {
      c->gpr[10] = 0;
      return schedule(c);
    }
    // 用户进程退出时标记 PCB，由 schedule() 负责释放地址空间。
    if ((int)c->GPR1 == SYS_exit) {
      current->cp = NULL;
      current->dead = 1;
      return schedule(c);
    }
    do_syscall(c);
    return c;
  default:
    panic("Unhandled event ID = %d", e.event);
  }
}

void init_irq(void)
{
  Log("Initializing interrupt/exception handler...");
  cte_init(do_event);
}
