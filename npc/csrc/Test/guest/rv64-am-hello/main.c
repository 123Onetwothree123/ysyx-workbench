#include <am.h>
#include <klib.h>
#include <klib-macros.h>

// 同时覆盖 RV32/RV64：打印指针宽度与二进制运算结果。
// 返回值 0 由 AM 的 halt() 转成 custom-0 GOOD TRAP。
int main(const char *args) {
  printf("AM hello: ISA=%s arch=%s\n", __ISA__, TOSTRING(__ARCH__));
  printf("AM hello: sizeof(uintptr_t)=%d mainargs=%s\n",
         (int)sizeof(uintptr_t), args);
  volatile uint64_t a = 0x0123456789abcdefULL;
  volatile uint64_t b = 0x0fedcba987654321ULL;
  volatile uint64_t c = (a ^ b) + (a >> 17) - (b >> 9);
  printf("AM hello: 64-bit mix hi=0x%x lo=0x%x\n",
         (unsigned)(c >> 32), (unsigned)c);
  return 0;
}
