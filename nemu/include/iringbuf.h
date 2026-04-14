// 文件是我自己创建的
#ifndef IRINGBUF_H_
#define IRINGBUF_H_
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <common.h>
#ifdef CONFIG_IRINGBUF
// 初始化
void IringbufInitialization(void);
// 记录一条命令
void RecordAInstruction(vaddr_t pc, uint32_t instruction, int len);
// 打印缓冲区
void PrintIringbuf(vaddr_t err_pc);
#else
static inline void IringbufInitialization(void) {}
static inline void RecordAInstruction(vaddr_t pc, uint32_t instruction, int len) {
  (void)pc;
  (void)instruction;
  (void)len;
}
static inline void PrintIringbuf(vaddr_t err_pc) { (void)err_pc; }
#endif
#endif
