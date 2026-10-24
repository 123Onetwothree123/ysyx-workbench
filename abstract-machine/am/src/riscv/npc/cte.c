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
// CLINT 的 mtimecmp 需要软件在每次中断后重新装载。
extern void __am_timer_set_next(void);

// 去掉中断位后的异常/中断 cause。
static inline uintptr_t irq_cause(Context *c) {
  return c->mcause & ~((uintptr_t)1 << (__riscv_xlen - 1));
}

Context *__am_irq_handle(Context *c) {
  __am_get_cur_as(c);

  if (user_handler) {
    Event ev = {0};
    // RV64 的中断位是 scause[63]，不能继续用 32 位掩码判断。
    if (c->mcause >> (__riscv_xlen - 1)) {
      // direct-NPC 的 CLINT 走 S 模式 STIP，cause=5。
      if (irq_cause(c) == 5) {
        ev.event = EVENT_IRQ_TIMER;
        // 先重新装载 mtimecmp，再返回被抢占的上下文；否则 STIP 会
        // 一直保持高电平，sret 后会立即重复进入同一个中断。
        __am_timer_set_next();
      } else {
        // 其余中断（例如 PLIC 外部中断）交给 AM 的通用设备事件。
        ev.event = EVENT_IRQ_IODEV;
      }
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

  // 装载第一个 10ms 时间片。此时 SIE 仍关闭，定时器到期后只会把
  // STIP 挂起，等第一次 sret 到 U 模式时才真正抢占用户进程。
  __am_timer_set_next();

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
  // 内核运行在 S 模式，全局中断使能位是 sstatus.SIE。
  uintptr_t sstatus;
  asm volatile("csrr %0, sstatus" : "=r"(sstatus));
  return (sstatus & MSTATUS_SIE) != 0;
}

void iset(bool enable) {
  // 只切换 SIE，保留 SPIE/SPP/SUM/MXR 等上下文相关字段。
  if (enable) {
    asm volatile("csrs sstatus, %0" : : "r"((uintptr_t)MSTATUS_SIE) : "memory");
  } else {
    asm volatile("csrc sstatus, %0" : : "r"((uintptr_t)MSTATUS_SIE) : "memory");
  }
}
