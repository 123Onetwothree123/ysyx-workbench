#include "types.h"
#include "param.h"
#include "memlayout.h"
#include "riscv.h"
#include "defs.h"

//
// the riscv Platform Level Interrupt Controller (PLIC).
//
// NPC decodes 0x0c000000 in its crossbar even though this window is not part
// of the PMA list; S-mode accesses are therefore legal.  The direct-NPC top
// routes UART RX interrupts to PLIC source 10 and its 0x2080/0x201000
// context registers, matching the QEMU reference layout.
//

void
plicinit(void)
{
  // set desired IRQ priorities non-zero (otherwise disabled).
  *(volatile uint32 *)(PLIC + UART0_IRQ * 4) = 1;
}

void
plicinithart(void)
{
  int hart = cpuid();

  // set enable bits for this hart's S-mode for the uart.
  *(volatile uint32 *)PLIC_SENABLE(hart) = (1 << UART0_IRQ);

  // set this hart's S-mode priority threshold to 0.
  *(volatile uint32 *)PLIC_SPRIORITY(hart) = 0;
}

// ask the PLIC what interrupt we should serve.
int
plic_claim(void)
{
  int hart = cpuid();
  int irq = *(volatile uint32 *)PLIC_SCLAIM(hart);
  return irq;
}

// tell the PLIC we've served this IRQ.
void
plic_complete(int irq)
{
  int hart = cpuid();
  *(volatile uint32 *)PLIC_SCLAIM(hart) = irq;
}
