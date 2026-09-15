#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <math.h>
#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
double fabs(double x)
{
  uint64_t u;
  memcpy(&u, &x, sizeof(u));
  u &= ~(1ULL << 63); // 清符号位
  memcpy(&x, &u, sizeof(x));
  return x;
}
float fabsf(float x)
{
  uint32_t u;
  memcpy(&u, &x, sizeof(u));
  u &= ~(1U << 31);
  memcpy(&x, &u, sizeof(x));
  return x;
}
double floor(double x)
{
  panic("floor: Not implemented");
}
double ceil(double x)
{
  panic("ceil: Not implemented");
}
double round(double x)
{
  panic("round: Not implemented");
}
double trunc(double x)
{
  panic("trunc: Not implemented");
}
double fmod(double x, double y)
{
  panic("fmod: Not implemented");
}
double sqrt(double x)
{
  panic("sqrt: Not implemented");
}
double cbrt(double x)
{
  panic("cbrt: Not implemented");
}
double pow(double x, double y)
{
  panic("pow: Not implemented");
}
double exp(double x)
{
  panic("exp: Not implemented");
}
double exp2(double x)
{
  panic("exp2: Not implemented");
}
double log(double x)
{
  panic("log: Not implemented");
}
double log2(double x)
{
  panic("log2: Not implemented");
}
double log10(double x)
{
  panic("log10: Not implemented");
}
double sin(double x)
{
  panic("sin: Not implemented");
}
double cos(double x)
{
  panic("cos: Not implemented");
}
double tan(double x)
{
  panic("tan: Not implemented");
}
double asin(double x)
{
  panic("asin: Not implemented");
}
double acos(double x)
{
  panic("acos: Not implemented");
}
double atan(double x)
{
  panic("atan: Not implemented");
}
double atan2(double y, double x)
{
  panic("atan2: Not implemented");
}
#endif
