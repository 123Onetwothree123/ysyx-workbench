#ifndef __PROC_H__
#define __PROC_H__

#include <common.h>
#include <memory.h>

#define STACK_SIZE (8 * PGSIZE)

typedef union {
  uint8_t stack[STACK_SIZE] PG_ALIGN;
  struct {
    Context *cp;
    AddrSpace as;
    // max_brk 记录已经映射到的最高位置，brk 是当前 program break。
    uintptr_t max_brk;
    uintptr_t brk;
    uintptr_t brk_min; // 程序初始的 program break，堆不能回收到它以下
    int pid; // 进程号
    int dead; // 进程是否已经退出
  };
} PCB;

extern PCB *current;

void context_uload(PCB *pcb, const char *filename, char *const argv[], char *const envp[]);
Context *schedule(Context *prev);

#endif
