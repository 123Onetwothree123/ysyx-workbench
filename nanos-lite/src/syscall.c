#include <common.h>
#include <memory.h>
#include <fs.h>
#include <proc.h>
#include "syscall.h"
// 打开下一行可输出系统调用跟踪
//#define STRACE
#ifdef STRACE
static const char *syscall_names[] = {
    [SYS_exit] = "exit",
    [SYS_yield] = "yield",
    [SYS_open] = "open",
    [SYS_read] = "read",
    [SYS_write] = "write",
    [SYS_kill] = "kill",
    [SYS_getpid] = "getpid",
    [SYS_close] = "close",
    [SYS_lseek] = "lseek",
    [SYS_brk] = "brk",
    [SYS_fstat] = "fstat",
    [SYS_time] = "time",
    [SYS_signal] = "signal",
    [SYS_execve] = "execve",
    [SYS_fork] = "fork",
    [SYS_link] = "link",
    [SYS_unlink] = "unlink",
    [SYS_wait] = "wait",
    [SYS_times] = "times",
    [SYS_gettimeofday] = "gettimeofday",
};
#endif
void do_syscall(Context *c)
{
  uintptr_t a[4];
  a[0] = c->GPR1;
  a[1] = c->GPR2;
  a[2] = c->GPR3;
  a[3] = c->GPR4;
  intptr_t ret = 0; // 系统调用的返回值，最后就直接统一返回到a0寄存器里面去
  switch (a[0])
  {
  case SYS_open:
    ret = fs_open((const char *)a[1], (int)a[2], (int)a[3]);
    break;
  case SYS_read:
    ret = fs_read((int)a[1], (void *)a[2], (size_t)a[3]);
    break;
  case SYS_write:
    ret = fs_write((int)a[1], (const void *)a[2], (size_t)a[3]);
    break;
  case SYS_close:
    ret = fs_close((int)a[1]);
    break;
  case SYS_lseek:
    ret = fs_lseek((int)a[1], (intptr_t)a[2], (int)a[3]);
    break;
  case SYS_getpid:
    ret = current->pid;
    break;
  case SYS_gettimeofday: {
    AM_TIMER_UPTIME_T uptime;
    ioe_read(AM_TIMER_UPTIME, &uptime);
    if (a[1] != 0) {
      uint32_t *tv = (uint32_t *)a[1];
      tv[0] = (uint32_t)(uptime.us / 1000000);
      tv[1] = (uint32_t)(uptime.us % 1000000);
    }
    ret = 0;
    break;
  }
  case SYS_brk:
    ret = mm_brk(a[1]);
  break;
  default:
    panic("Unhandled syscall ID = %d", a[0]);
  }
  c->GPRx = ret; // 设置返回值，到时候用户侧从a0就可以直接去读到
#ifdef STRACE
  Log("[strace] %s(0x%x, 0x%x, 0x%x) = %d", syscall_names[a[0]], a[1], a[2], a[3], ret);
#endif
}
