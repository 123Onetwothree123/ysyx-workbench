#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>

static Context *(*user_handler)(Event, Context *) = NULL;

extern void __am_get_cur_as(Context *c);
extern void __am_switch(Context *c);

Context *__am_irq_handle(Context *c)
{
  __am_get_cur_as(c);

  if (user_handler)
  {
    Event ev = {0};
    switch (c->mcause)
    {
    case 8:
    case 9:
    case 11:
      c->mepc += 4;
#ifdef __riscv_e
      if ((intptr_t)c->gpr[15] == -1)
#else
      if ((intptr_t)c->gpr[17] == -1)
#endif
      {
        ev.event = EVENT_YIELD;
      }
      else
      {
        ev.event = EVENT_SYSCALL;
      }
      break;
    default:
      printf("未处理的异常 cause=%d\n", (int)c->mcause);
      ev.event = EVENT_ERROR;
      break;
    }

    c = user_handler(ev, c);
    assert(c != NULL);
  }

  __am_switch(c);

  return c;
}

extern void __am_asm_trap(void);
extern void __am_enter_supervisor(void);

bool cte_init(Context *(*handler)(Event, Context *))
{
  // 第一次陷入时还没有进程上下文，先把当前启动栈顶作为内核栈顶。
  asm volatile("csrw sscratch, sp" ::: "memory");
  // 初始化 S 模式异常入口。
  asm volatile("csrw stvec, %0" : : "r"(__am_asm_trap));

  // 将用户和内核异常委托给 S 模式处理：非法指令、断点、U/S ecall。
  const uintptr_t medeleg = (1u << 2) | (1u << 3) | (1u << 8) | (1u << 9);
  asm volatile("csrw medeleg, %0" : : "r"(medeleg));
  asm volatile("csrw mideleg, %0" : : "r"((uintptr_t)0));

  // 注册事件处理函数。
  user_handler = handler;

  // 从 M 模式切换到 S 模式，之后内核代码运行在 S 模式。
  __am_enter_supervisor();

  return true;
}

Context *kcontext(Area kstack, void (*entry)(void *), void *arg)
{
  // return NULL;
  Context *c = (Context *)(kstack.end - sizeof(Context)); // end是栈最高位然后减掉长度，找开头部分建指针
  memset(c, 0, sizeof(Context));                          // 和rtthreadam一样，ai建议手动清零
  c->mstatus = (MODE_S << 8) | MSTATUS_SUM | MSTATUS_MXR; // SPP=S，允许 S 访问用户页
  c->mepc = (uintptr_t)entry;
  c->gpr[10] = (uintptr_t)arg; // riscv elf psabi写的是参数写到a0
  c->gpr[2] = (uintptr_t)kstack.end; // trap.S 从 gpr[2] 恢复 sp
  c->pdir = NULL;              // 内核线程留在当前地址空间
  return c;
}

void yield()
{
#ifdef __riscv_e
  asm volatile("li a5, -1; ecall");
#else
  asm volatile("li a7, -1; ecall");
#endif
}

bool ienabled()
{
  return false;
}

void iset(bool enable)
{
}
