#ifndef KLIB_MATH_H__
#define KLIB_MATH_H__
#ifdef __cplusplus
extern "C"
{
#endif
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <math.h>
#else
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#ifndef M_E
#define M_E 2.71828182845904523536
#endif
double fabs(double x);
float fabsf(float x);
double floor(double x);
double ceil(double x);
double round(double x);
double trunc(double x);
double fmod(double x, double y);
double sqrt(double x);
double cbrt(double x);
double pow(double x, double y);
double exp(double x);
double exp2(double x);
double log(double x);
double log2(double x);
double log10(double x);
double sin(double x);
double cos(double x);
double tan(double x);
double asin(double x);
double acos(double x);
double atan(double x);
double atan2(double y, double x);
#endif
#ifdef __cplusplus
}
#endif
#endif
