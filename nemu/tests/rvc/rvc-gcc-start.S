/*
 * GCC C 镜像的裸机启动代码。
 * 只负责设置栈、调用 C 函数，并用 a0 作为 NEMU custom-0 停机码。
 */
.option arch, +zicsr
.option rvc

.section .text
.globl _start
_start:
  la      sp, c_stack_top
  call    c_test_main
  .word   0x0000000b

.section .data
.balign 16
.space  4096
.globl c_stack_top
c_stack_top:
