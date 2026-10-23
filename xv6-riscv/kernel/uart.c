//
// low-level driver for 16550a UART.
//
// On NPC the UART model exposes only the transmit holding register at
// 0x10000000.  Reads (LSR/RHR/ISR) are DECERR and there is no input path,
// so the NPC build writes THR directly and polls nothing.  The QEMU build
// keeps the interrupt-driven 16550 path as a reference.
//

#include "types.h"
#include "param.h"
#include "memlayout.h"
#include "riscv.h"
#include "spinlock.h"
#include "sleeplock.h"
#include "proc.h"
#include "defs.h"

// the UART control registers are memory-mapped
// at address UART0. this macro returns the
// address of one of the registers.
#define Reg(reg) ((volatile unsigned char *)(UART0 + (reg)))

#define ReadReg(reg)     (*(Reg(reg)))
#define WriteReg(reg, v) (*(Reg(reg)) = (v))

// the UART control registers.
// some have different meanings for read vs write.
// see http://byterunner.com/16550.html
#define RHR             0        // receive holding register (for input bytes)
#define THR             0        // transmit holding register (for output bytes)
#define IER             1        // interrupt enable register
#define IER_RX_ENABLE   (1 << 0) // receiver interrupts
#define IER_TX_ENABLE   (1 << 1) // transmit interrupts
#define FCR             2        // FIFO control register
#define FCR_FIFO_ENABLE (1 << 0)
#define FCR_FIFO_CLEAR  (3 << 1) // clear the content of the two FIFOs
#define ISR             2        // interrupt status register
#define LCR             3        // line control register
#define LCR_EIGHT_BITS  (3 << 0)
#define LCR_BAUD_LATCH  (1 << 7) // special mode to set baud rate
#define LSR             5        // line status register
#define LSR_RX_READY    (1 << 0) // input is waiting to be read from RHR
#define LSR_TX_IDLE     (1 << 5) // THR can accept another character to send

// for sending threads to serialize their writes
static struct sleeplock tx_lock;

#ifndef XV6_PLATFORM_NPC
static int tx_chan; // &tx_chan is the "wait channel"
#endif

extern volatile int panicking; // from printk.c
extern volatile int panicked;  // from printk.c

void
uartinit(void)
{
#ifdef XV6_PLATFORM_NPC
  // No register initialization is possible: NPC only accepts a store to
  // 0x10000000.  The THR is always ready from the software point of view.
  initsleeplock(&tx_lock, "uart");
#else
  // disable interrupts.
  WriteReg(IER, 0x00);

  // special mode to set baud rate.
  WriteReg(LCR, LCR_BAUD_LATCH);

  // LSB for baud rate of 38.4K.
  WriteReg(0, 0x03);

  // MSB for baud rate of 38.4K.
  WriteReg(1, 0x00);

  // leave set-baud mode,
  // and set word length to 8 bits, no parity.
  WriteReg(LCR, LCR_EIGHT_BITS);

  // reset and enable FIFOs.
  WriteReg(FCR, FCR_FIFO_ENABLE | FCR_FIFO_CLEAR);

  // enable transmit and receive interrupts.
  WriteReg(IER, IER_TX_ENABLE | IER_RX_ENABLE);

  initsleeplock(&tx_lock, "uart");
#endif
}

// transmit buf[] to the uart. it blocks if the
// uart is busy, so it cannot be called from
// interrupts, only from write() system calls.
void
uartwrite(char buf[], int n)
{
  acquiresleep(&tx_lock);

#ifdef XV6_PLATFORM_NPC
  // NPC has no LSR and no TX interrupt; a THR store always consumes the
  // byte in the model, so drain the buffer synchronously.
  for (int i = 0; i < n; i++)
    uartputc_sync(buf[i]);
#else
  int i = 0;
  while (i < n) {
    sleep_prepare(&tx_chan);
    if (ReadReg(LSR) & LSR_TX_IDLE) {
      WriteReg(THR, buf[i]);
      i += 1;
    } else {
      sleep();
    }
  }
#endif

  releasesleep(&tx_lock);
}

// write a byte to the uart without using
// interrupts, for use by kernel printk() and
// to echo characters. it spins waiting for the uart's
// output register to be empty.
void
uartputc_sync(int c)
{
  if (panicking == 0)
    push_off();

  if (panicked) {
    for (;;)
      ;
  }

#ifdef XV6_PLATFORM_NPC
  // Write-only UART model: no LSR to poll.
  WriteReg(THR, c);
#else
  // wait for UART to set Transmit Holding Empty in LSR.
  while ((ReadReg(LSR) & LSR_TX_IDLE) == 0)
    ;
  WriteReg(THR, c);
#endif

  if (panicking == 0)
    pop_off();
}

// try to read one input character from the uart.
// return -1 if none is waiting.
#ifndef XV6_PLATFORM_NPC
static int
uartgetc(void)
{
  // is input ready?
  if (ReadReg(LSR) & LSR_RX_READY) {
    return ReadReg(RHR);
  } else {
    return -1;
  }
}
#endif

// handle a uart interrupt, raised because input has
// arrived, or the uart is ready for more output, or
// both. called from devintr().
void
uartintr(void)
{
#ifdef XV6_PLATFORM_NPC
  // No UART interrupt source is wired in the direct-NPC top.
#else
  ReadReg(ISR); // acknowledge the interrupt

  if (ReadReg(LSR) & LSR_TX_IDLE) {
    // UART finished transmitting; wake up sending thread.
    wakeup(&tx_chan);
  }

  // read and process incoming characters, if any.
  while (1) {
    int c = uartgetc();
    if (c == -1)
      break;
    consoleintr(c);
  }
#endif
}
