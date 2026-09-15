#ifndef KLIB_CTYPE_H__
#define KLIB_CTYPE_H__
#ifdef __cplusplus
extern "C"
{
#endif
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <ctype.h>
#else
int isalnum(int c);
int isalpha(int c);
int isblank(int c);
int iscntrl(int c);
int isdigit(int c);
int isgraph(int c);
int islower(int c);
int isprint(int c);
int ispunct(int c);
int isspace(int c);
int isupper(int c);
int isxdigit(int c);
int tolower(int c);
int toupper(int c);
#endif
#ifdef __cplusplus
}
#endif
#endif
