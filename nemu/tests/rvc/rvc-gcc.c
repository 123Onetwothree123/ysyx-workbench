/*
 * GCC 生成的 RV32C/RV64C C 语言镜像（-Os -march=rv{32,64}imac）。
 * 不使用 libc，返回值 0 表示成功；NEMU 用 custom-0 停机。
 */
static unsigned crc32_simple(const unsigned char *data, int n)
{
  unsigned crc = 0xffffffffu;
  for (int i = 0; i < n; i++)
  {
    crc ^= data[i];
    for (int bit = 0; bit < 8; bit++)
    {
      crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
  }
  return ~crc;
}

static unsigned fib(unsigned n)
{
  unsigned a = 0, b = 1;
  while (n-- != 0)
  {
    unsigned next = a + b;
    a = b;
    b = next;
  }
  return a;
}

int c_test_main(void)
{
  unsigned char buf[32];
  for (int i = 0; i < (int)sizeof(buf); i++)
  {
    buf[i] = (unsigned char)(i * 37 + 11);
  }

  if (crc32_simple(buf, (int)sizeof(buf)) != 0xa9f48115u)
  {
    return 1;
  }
  if (fib(10) != 55)
  {
    return 2;
  }

#if __riscv_xlen == 64
  /* RV64C 镜像同时覆盖 64 位运算与 C.ADDIW/C.LD/C.SD 等。 */
  unsigned long wide = 0x0123456789abcdefUL;
  wide = (wide << 13) | (wide >> 51);
  if (wide != 0x68acf13579bde024UL)
  {
    return 3;
  }
#endif

  return 0;
}
