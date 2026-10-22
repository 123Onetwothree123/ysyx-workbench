#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>

/*
 * direct-NPC 的 CTE 实现。
 *
 * 与 NEMU 平台一致：cte_init() 把异常入口设为 stvec，将常见异常和
 * S 模式定时器中断委托给 S 模式，然后从 M 模式切换到 S 模式。之后
 * Nanos-lite 内核运行在 S 模式，用户进程通过 satp/Sv32 运行在 U 模式。
 *
 * direct-NPC 当前默认 HAS_PMP=n，因此不能像 NEMU 那样写 pmpcfg0；
 * 缺省配置下所有物理地址均可访问，交给 PMA 做地址合法性检查。
 */

static Context *(*user_handler)(Event, Context *) = NULL;

extern void __am_get_cur_as(Context *c);
extern void __am_switch(Context *c);

Context *__am_irq_handle(Context *c) {
  __am_get_cur_as(c);

  if (user_handler) {
    Event ev = {0};
    // RV64 的中断位是 scause[63]，不能继续用 32 位掩码判断。
    if (c->mcause >> (__riscv_xlen - 1)) {
      // 目前只接入时钟中断（cause=5）。
      ev.event = EVENT_IRQ_TIMER;
    } else {
      switch (c->mcause) {
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
        } else {
          ev.event = EVENT_SYSCALL;
        }
        break;
      default:
        printf("未处理的异常 cause=%d\n", (int)c->mcause);
        ev.event = EVENT_ERROR;
        break;
      }
    }

    c = user_handler(ev, c);
    assert(c != NULL);
  }

  __am_switch(c);

  return c;
}

extern void __am_asm_trap(void);
extern void __am_enter_supervisor(void);

bool cte_init(Context *(*handler)(Event, Context *)) {
  // sscratch=0 表示当前已经在 S 模式内核栈上，不需要栈切换。
  asm volatile("csrw sscratch, zero" ::: "memory");
  // 初始化 S 模式异常入口。
  asm volatile("csrw stvec, %0" : : "r"(__am_asm_trap));

  // 将常见异常委托给 S 模式：取指/访存/页错误、非法指令、断点、U/S ecall。
  const uintptr_t medeleg =
      (1u << 1) | (1u << 2) | (1u << 3) | (1u << 4) | (1u << 5) |
      (1u << 6) | (1u << 7) | (1u << 8) | (1u << 9) |
      (1u << 12) | (1u << 13) | (1u << 15);
  asm volatile("csrw medeleg, %0" : : "r"(medeleg));
  // 将 S 模式时钟中断（cause 5）委托给 S 模式处理。
  const uintptr_t mideleg = (1u << 5);
  const uintptr_t mie = (1u << 5); // STIE
  asm volatile("csrw mideleg, %0" : : "r"(mideleg));
  asm volatile("csrw mie, %0" : : "r"(mie));
  const uintptr_t all_counters = 0xffffffffu;
  asm volatile("csrw mcounteren, %0" : : "r"(all_counters));
  asm volatile("csrw scounteren, %0" : : "r"(all_counters));

  // 注册事件处理函数。
  user_handler = handler;

  // 从 M 模式切换到 S 模式，之后内核代码运行在 S 模式。
  __am_enter_supervisor();

  return true;
}

Context *kcontext(Area kstack, void (*entry)(void *), void *arg) {
  Context *c = (Context *)(kstack.end - sizeof(Context));
  memset(c, 0, sizeof(Context));
  c->mstatus = (MODE_S << 8) | MSTATUS_SPIE | MSTATUS_SUM | MSTATUS_MXR |
               MSTATUS_SXL | MSTATUS_UXL;
  c->mepc = (uintptr_t)entry;
  c->gpr[10] = (uintptr_t)arg; // riscv elf psabi 把第一个参数放在 a0
  // 内核栈顶部保留一个 trap frame，内核线程从它下面开始使用栈。
  c->gpr[2] = (uintptr_t)kstack.end - ((sizeof(Context) + 15) & ~(size_t)15);
  c->pdir = NULL;              // 内核线程留在当前地址空间
  return c;
}

void yield() {
#ifdef __riscv_e
  asm volatile("li a5, -1; ecall");
#else
  asm volatile("li a7, -1; ecall");
#endif
}

bool ienabled() {
  return false;
}

void iset(bool enable) {
}
