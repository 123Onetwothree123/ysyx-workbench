#include <common.h>
#include "syscall.h"
#define STRACE
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
  case SYS_yield:
    yield();
    ret = 0;
    break;
  case SYS_exit:
#ifdef STRACE
    Log("[strace] exit(%d)", (int)a[1]);
#endif
    halt(a[1]);
    break;
  case SYS_write:
    if (a[1] == 1 || a[1] == 2) // 1是stdout，2是stderr，a1是fd，a2是buf，a3是len
    {
      for (size_t i = 0; i < a[3]; i++)
      {
        putch(((char *)a[2])[i]); // 又忘记了，这是am的putch函数
      }
      ret = a[3]; // 返回写出的字节数
    }
    else
    {
      ret = -1; // 其他fd先不管了
    }
    break;
  case SYS_brk:
    ret = 0;
  break;
  default:
    panic("Unhandled syscall ID = %d", a[0]);
  }
  c->GPRx = ret; // 设置返回值，到时候用户侧从a0就可以直接去读到
#ifdef STRACE
  Log("[strace] %s(0x%x, 0x%x, 0x%x) = %d", syscall_names[a[0]], a[1], a[2], a[3], ret);
#endif
}
