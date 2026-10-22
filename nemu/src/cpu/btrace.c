#include <cpu/btrace.h>
#include <stdio.h>
#include <stdlib.h>

static FILE *btrace_fp = NULL;

static void btrace_close(void) {
    if (btrace_fp) {
        fclose(btrace_fp);
        btrace_fp = NULL;
    }
}

void btrace_write(vaddr_t pc, vaddr_t target, bool taken, char kind,
                  word_t jalr_imm) {
    if (btrace_fp == NULL) {
        btrace_fp = fopen("build/btrace.txt", "w");
        if (btrace_fp == NULL) {
            // 当前目录下没有build目录时退而写到当前目录
            btrace_fp = fopen("btrace.txt", "w");
        }
        if (btrace_fp) {
            atexit(btrace_close);
        }
    }
    if (btrace_fp) {
#ifdef CONFIG_ISA64
        // btrace 列宽跟随 XLEN，避免 RV64 地址被截成 32 位。
        fprintf(btrace_fp, "%016" PRIx64 " %016" PRIx64 " %d %c %016" PRIx64 "\n",
                (uint64_t)pc, (uint64_t)target, taken ? 1 : 0, kind,
                (uint64_t)jalr_imm);
#else
        fprintf(btrace_fp, "%08x %08x %d %c %08x\n",
                pc, target, taken ? 1 : 0, kind, jalr_imm);
#endif
    }
}
