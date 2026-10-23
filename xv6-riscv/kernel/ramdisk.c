//
// RAM disk backend for xv6 on NPC.
//
// virtio is not available in the NPC direct-simulation platform.  Instead,
// fs.img is linked into the kernel by kernel/ramdisk.S as the writable
// .ramdisk section.  Block I/O is a synchronous memmove into that region;
// writes persist until the simulator is reset, which is exactly the
// semantics of a ramdisk.
//

#include "types.h"
#include "param.h"
#include "memlayout.h"
#include "riscv.h"
#include "spinlock.h"
#include "sleeplock.h"
#include "defs.h"
#include "fs.h"
#include "buf.h"

extern char _ramdisk_start[];
extern char _ramdisk_end[];

static struct {
  struct spinlock lock;
  uint64 size;
} ramdisk;

void
ramdisk_init(void)
{
  initlock(&ramdisk.lock, "ramdisk");

  ramdisk.size = (uint64)_ramdisk_end - (uint64)_ramdisk_start;
  if (ramdisk.size < (uint64)FSSIZE * BSIZE)
    panic("ramdisk too small");
  if (ramdisk.size % BSIZE)
    panic("ramdisk size");
}

// Read or write one xv6 block (BSIZE bytes).  The buffer cache serializes
// access to individual buffers; the ramdisk lock protects the backing store
// when different buffers are written concurrently.
void
ramdisk_rw(struct buf *b, int write)
{
  uint64 off = (uint64)b->blockno * BSIZE;

  if (b->blockno >= FSSIZE || off + BSIZE > ramdisk.size)
    panic("ramdisk block out of range");

  acquire(&ramdisk.lock);
  if (write)
    memmove(_ramdisk_start + off, b->data, BSIZE);
  else
    memmove(b->data, _ramdisk_start + off, BSIZE);
  release(&ramdisk.lock);

  b->disk = 0; // no asynchronous device owns the buffer
}
