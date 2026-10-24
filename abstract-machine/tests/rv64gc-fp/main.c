#include <am.h>
#include <klib.h>
#include <klib-macros.h>

// RV64GC / lp64d 浮点上下文测试。
//
// 两个内核上下文交替运行，每个上下文反复执行 fp_probe()：
//   * 写入不同的 double/float 到 f0-f31，并写入带 tag 的 fcsr；
//   * ecall 让 AM 调度到另一个上下文（期间也可能被时钟中断抢占）；
//   * 恢复后逐项校验 f0-f31/fcsr。
// 如果 trap.S 漏保存浮点寄存器，或 kcontext()/ucontext() 没有置
// mstatus.FS，测试会立刻打印失败编号或触发非法指令。

#define NTASK 2
#ifndef ITERS
#define ITERS 16
#endif
#define TASK_STACK_SIZE 8192

extern int fp_probe(uint64_t tag);

static char task_stack[NTASK][TASK_STACK_SIZE] __attribute__((aligned(16)));

static Context *task_ctx[NTASK];
static Context *main_ctx;
static volatile int finished[NTASK];
static volatile int current;
static volatile int switches;
static volatile int timer_irqs;
static volatile int fp_rounds;

static Context *schedule(Event ev, Context *c) {
  if (ev.event == EVENT_IRQ_TIMER) {
    timer_irqs++;
  }

  if (main_ctx == NULL) {
    // 第一次陷入来自 main 的 yield()，保存它以便两个任务结束后返回。
    main_ctx = c;
    current = 0;
    switches++;
    return task_ctx[0];
  }

  // 保存刚被抢占/主动让出的任务现场；下次恢复这个指针，而不是初始
  // kcontext() 现场，否则任务会从入口重新开始。
  task_ctx[current] = c;

  if (finished[0] && finished[1]) {
    switches++;
    return main_ctx;
  }

  current ^= 1;
  switches++;
  return task_ctx[current];
}

static void fp_task(void *arg) {
  int id = (int)(uintptr_t)arg;

  for (int i = 0; i < ITERS; i++) {
    // tag 在任务间和迭代间都不同，fcsr 的 frm/fflags 也随之变化。
    uint64_t tag = (uint64_t)id * 32 + (uint64_t)i;
    int bad = fp_probe(tag);
    if (bad != 0) {
      printf("FP context mismatch: task=%d iter=%d fail_code=%d\n",
             id, i, bad);
      halt(1);
    }
    fp_rounds++;
    if ((i & 3) == 3) {
      printf("task %d: iter %d OK\n", id, i);
    }
  }

  finished[id - 1] = 1;
  printf("task %d: %d FP context rounds OK\n", id, ITERS);

  // 让调度器继续运行另一个任务；两个任务都结束后由 schedule() 返回 main。
  for (;;) {
    yield();
  }
}

static void hard_float_sanity(void) {
  volatile double a = 1.5, b = 2.25, c = 0.25;
  volatile double d = a * b + c;
  volatile float x = 1.5f, y = 2.5f, z = 0.75f;
  volatile float f = x * y - z;

  if (d != 3.625) {
    printf("hard-float double sanity failed: %x\n", (unsigned)(uint64_t)(d * 1000));
    halt(1);
  }
  if (f != 3.0f) {
    printf("hard-float float sanity failed: %x\n", (unsigned)(uint64_t)(f * 1000));
    halt(1);
  }
}

int main(void) {
  printf("RV64GC FP context test: f0-f31 + fcsr + mstatus.FS\n");

  cte_init(schedule);

  // 这段 C 代码由 -mabi=lp64d 编译；若链接到软浮点 helper 或 ABI
  // 不一致，这里的算术结果会直接失败，check-isa 还会审计符号。
  // 放在 cte_init 之后还有一个目的：此时 __am_enter_supervisor 已从
  // M 模式 mret 到 S 模式，若它没有设置 mstatus.FS，这里会立刻非法。
  hard_float_sanity();

  for (int i = 0; i < NTASK; i++) {
    task_ctx[i] = kcontext(
        RANGE(task_stack[i], task_stack[i] + TASK_STACK_SIZE),
        fp_task, (void *)(uintptr_t)(i + 1));
  }

  yield();

  printf("back to main: fp_rounds=%d switches=%d timer_irqs=%d\n",
         fp_rounds, switches, timer_irqs);

  if (!finished[0] || !finished[1]) {
    printf("tasks did not finish\n");
    halt(1);
  }
  if (fp_rounds != NTASK * ITERS) {
    printf("unexpected fp_rounds=%d\n", fp_rounds);
    halt(1);
  }
  if (timer_irqs == 0) {
    printf("warning: no timer interrupt observed (yield switching still tested)\n");
  }

  printf("RV64GC FP context test PASSED\n");
  return 0;
}
