#include "types.h"
#include "param.h"
#include "memlayout.h"
#include "riscv.h"
#include "defs.h"

void main();
void timerinit();
void timervec();

// entry.S needs one stack per CPU.
__attribute__((aligned(16))) char stack0[4096 * NCPU];

// timervec saves a0-a5 here and reads the CLINT address/interval from
// scratch[3]/scratch[4].
__attribute__((aligned(64))) uint64 timer_scratch[NCPU][7];

#define TIMER_INTERVAL 1000000

// entry.S jumps here in machine mode on stack0.
void
start()
{
  // set M Previous Privilege mode to Supervisor, for mret.
  unsigned long x = r_mstatus();
  x &= ~MSTATUS_MPP_MASK;
  x |= MSTATUS_MPP_S;
  w_mstatus(x);

  // set M Exception Program Counter to main, for mret.
  // requires gcc -mcmodel=medany
  w_mepc((uint64)main);

  // disable paging for now.
  w_satp(0);

  // Delegate exceptions and the S-level interrupt sources to supervisor
  // mode.  Do NOT delegate MTIP (bit 7): NPC keeps the machine timer as an
  // M-mode interrupt and timervec converts it into SSIP.
  w_medeleg(0xffff);
  w_mideleg(SIE_SEIE | SIE_STIE | SIE_SSIE);
  w_sie(r_sie() | SIE_SEIE | SIE_STIE | SIE_SSIE);

#ifdef XV6_PLATFORM_NPC
  // NPC has no PMP (CONFIG_HAS_PMP=n); writing pmpcfg0/pmpaddr0 would raise
  // an illegal-instruction trap.  M-mode and S-mode have full access.
#else
  // configure Physical Memory Protection to give supervisor mode
  // access to all of physical memory.
  w_pmpaddr0(0x3fffffffffffffull);
  w_pmpcfg0(0xf);
#endif

  // allow supervisor mode to read the time CSR (not required by the timer
  // path, but keeps rdtime usable for debugging).
  w_mcounteren(r_mcounteren() | 2);

  // ask for clock interrupts.
  timerinit();

  // keep each CPU's hartid in its tp register, for cpuid().
#ifdef XV6_PLATFORM_NPC
  // NPC does not implement mhartid; it is a single-hart machine.
  w_tp(0);
#else
  int id = r_mhartid();
  w_tp(id);
#endif

  // switch to supervisor mode and jump to main().
  asm volatile("mret");
}

// ask each hart to generate timer interrupts.
void
timerinit()
{
#ifdef XV6_PLATFORM_NPC
  int id = 0; // single hart, and mhartid is not implemented
#else
  int id = r_mhartid();
#endif
  uint64 *scratch = &timer_scratch[id][0];

  scratch[3] = CLINT_MTIMECMP(id);
  scratch[4] = TIMER_INTERVAL;

  w_mscratch((uint64)scratch);
  w_mtvec((uint64)timervec);
  w_mie(r_mie() | MIE_MTIE);

  // NPC's crossbar rejects multi-beat writes to the CLINT window, so a
  // 64-bit `sd` to mtimecmp would return DECERR.  Program the two 32-bit
  // halves explicitly, as the CLINT slave expects.
  volatile uint32 *cmp = (volatile uint32 *)CLINT_MTIMECMP(id);
  volatile uint32 *mtime = (volatile uint32 *)CLINT_MTIME;
  uint64 now = ((uint64)mtime[1] << 32) | mtime[0];
  uint64 next = now + TIMER_INTERVAL;

  cmp[1] = (uint32)(next >> 32);
  cmp[0] = (uint32)next;
}
