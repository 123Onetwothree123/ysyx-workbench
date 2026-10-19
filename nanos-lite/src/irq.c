#include <common.h>
#include <proc.h>
#include "syscall.h"

void do_syscall(Context *c);

static Context *do_event(Event e, Context *c)
{
  switch (e.event)
  {
  case EVENT_YIELD:
    return schedule(c);
  case EVENT_IRQ_TIMER:
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
