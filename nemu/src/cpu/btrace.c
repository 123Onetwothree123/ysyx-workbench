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

void btrace_write(vaddr_t pc, vaddr_t target, bool taken) {
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
        fprintf(btrace_fp, "%08x %08x %d\n", pc, target, taken ? 1 : 0);
    }
}