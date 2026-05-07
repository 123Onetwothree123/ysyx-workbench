#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>
#include <stddef.h>

static Context* (*user_handler)(Event, Context*) = NULL;

Context* __am_irq_handle(Context *c) {
  // --- DEBUG: verify Context layout ---
  printf("===== Context Debug =====\n");
  printf("sizeof(Context) = %d, expected %d\n", (int)sizeof(Context), (int)((NR_REGS + 3) * sizeof(uintptr_t) + sizeof(void*)));
  printf("offsetof gpr    = %d (expect 0)\n", (int)offsetof(Context, gpr));
  printf("offsetof mcause = %d (expect %d)\n", (int)offsetof(Context, mcause), (int)(NR_REGS * sizeof(uintptr_t)));
  printf("offsetof mstatus= %d (expect %d)\n", (int)offsetof(Context, mstatus), (int)((NR_REGS + 1) * sizeof(uintptr_t)));
  printf("offsetof mepc   = %d (expect %d)\n", (int)offsetof(Context, mepc), (int)((NR_REGS + 2) * sizeof(uintptr_t)));
  printf("offsetof pdir   = %d (expect %d)\n", (int)offsetof(Context, pdir), (int)((NR_REGS + 3) * sizeof(uintptr_t)));
  printf("mepc    = 0x%08x\n", c->mepc);
  printf("mcause  = 0x%08x\n", c->mcause);
  printf("mstatus = 0x%08x\n", c->mstatus);
  printf("gpr[0]=%08x gpr[1](ra)=%08x gpr[2](sp)=%08x\n", c->gpr[0], c->gpr[1], c->gpr[2]);
  printf("gpr[10](a0)=%08x gpr[17](a7)=%08x\n", c->gpr[10], c->gpr[17]);
  printf("=========================\n");
  // --- END DEBUG ---

  if (user_handler) {
    Event ev = {0};
    switch (c->mcause) {
      default: ev.event = EVENT_ERROR; break;
    }

    c = user_handler(ev, c);
    assert(c != NULL);
  }

  return c;
}

extern void __am_asm_trap(void);

bool cte_init(Context*(*handler)(Event, Context*)) {
  // initialize exception entry
  asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));

  // register event handler
  user_handler = handler;

  return true;
}

Context *kcontext(Area kstack, void (*entry)(void *), void *arg) {
  return NULL;
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
