#include <proc.h>

#define MAX_NR_PROC 4

static PCB pcb[MAX_NR_PROC] __attribute__((used)) = {};
static PCB pcb_boot = {};
PCB *current = NULL;

uintptr_t loader(PCB *pcb, const char *filename);
void __am_switch_to_kernel(void);

void switch_boot_pcb() {
  current = &pcb_boot;
}

void hello_fun(void *arg) {
  int j = 1;
  while (1) {
    Log("Hello World from Nanos-lite with arg '%p' for the %dth time!", (uintptr_t)arg, j);
    j ++;
    yield();
  }
}

static void __attribute__((unused)) context_kload(PCB *pcb, void (*entry)(void *), void *arg) {
  Area kstack = { .start = pcb->stack, .end = pcb->stack + STACK_SIZE };
  pcb->as.ptr = NULL; // 内核线程没有自己的用户地址空间
  pcb->cp = kcontext(kstack, entry, arg);
  assert(pcb->cp != NULL);
}

void context_uload(PCB *pcb, const char *filename, char *const argv[], char *const envp[]) {
  (void)argv;
  (void)envp;

  // 重复加载同一个 PCB 时，先释放旧地址空间，避免页表和用户页泄漏。
  if (pcb->as.ptr != NULL) {
    if (pcb == current) {
      __am_switch_to_kernel();
    }
    unprotect(&pcb->as);
  }
  pcb->cp = NULL;
  pcb->dead = 0;
  pcb->max_brk = 0;
  pcb->brk = 0;
  pcb->brk_min = 0;

  protect(&pcb->as);

  uintptr_t entry = loader(pcb, filename);

  // 映射用户栈：[as.area.end - STACK_SIZE, as.area.end)。
  uintptr_t stack_low = (uintptr_t)pcb->as.area.end - STACK_SIZE;
  for (uintptr_t va = stack_low; va < (uintptr_t)pcb->as.area.end; va += PGSIZE) {
    void *pa = new_page(1);
    assert(pa != NULL);
    memset(pa, 0, PGSIZE);
    map(&pcb->as, (void *)va, pa, 0);
  }

  Area kstack = { .start = pcb->stack, .end = pcb->stack + STACK_SIZE };
  pcb->cp = ucontext(&pcb->as, kstack, (void *)entry);
  assert(pcb->cp != NULL);

}

void init_proc() {
  switch_boot_pcb();

  Log("Initializing processes...");

  /*
   * PA4.4 的两个用户进程。
   *
   * 讲义示例使用 /bin/pal + /bin/hello；本仓库没有 pal-navy 的
   * 源码和游戏数据，无法生成真实的 pal 镜像，因此使用两个等价的
   * 无限循环用户程序：
   *   - /bin/hello 持续打印 Hello World；
   *   - /bin/spin  持续做普通循环并周期打印，从不调用 yield。
   * 两者都由 S 模式时钟中断抢占，日志交替出现即可证明时间片轮转。
   */
  context_uload(&pcb[0], "/bin/hello", NULL, NULL);
  pcb[0].pid = 1;

  context_uload(&pcb[1], "/bin/spin", NULL, NULL);
  pcb[1].pid = 2;
}

Context* schedule(Context *prev) {
  static int next = 0;

  if (current->dead) {
    // 先切到内核页目录，再释放即将退出进程的用户地址空间。
    __am_switch_to_kernel();
    if (current->as.ptr != NULL) {
      unprotect(&current->as);
    }
    current->cp = NULL;
    current->max_brk = 0;
    current->brk = 0;
    current->brk_min = 0;
    current->dead = 0;
  } else {
    // 保存即将离开的进程上下文。
    current->cp = prev;
  }

  // 内核线程没有用户地址空间，不能继承当前进程的页表。
  if (current->as.ptr == NULL && current->cp != NULL) {
    current->cp->pdir = NULL;
  }

  // 在 PCB 数组中轮转。内核线程的 cp != NULL 且 pdir == NULL，
  // 因此 __am_switch() 不会切换地址空间。
  for (int i = 0; i < MAX_NR_PROC; i++) {
    int idx = (next + i) % MAX_NR_PROC;
    if (pcb[idx].cp != NULL) {
      current = &pcb[idx];
      next = (idx + 1) % MAX_NR_PROC;
      return current->cp;
    }
  }

  halt(0);
  return NULL;
}
