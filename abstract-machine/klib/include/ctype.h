#ifndef KLIB_CTYPE_H__
#define KLIB_CTYPE_H__
#ifdef __cplusplus
extern "C"
{
#endif
#if defined(__ISA_NATIVE__) && !defined(__NATIVE_USE_KLIB__)
#include_next <ctype.h>
#else

/* Character-class masks expected by common libstdc++ target headers. */
#ifndef _U
#define _U 01
#define _L 02
#define _N 04
#define _S 010
#define _P 020
#define _C 040
#define _X 0100
#define _B 0200
#endif

#ifndef _ISbit
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#define _ISbit(bit) (1 << (bit))
#else
#define _ISbit(bit) ((bit) < 8 ? ((1 << (bit)) << 8) : ((1 << (bit)) >> 8))
#endif
enum {
  _ISupper = _ISbit(0),
  _ISlower = _ISbit(1),
  _ISalpha = _ISbit(2),
  _ISdigit = _ISbit(3),
  _ISxdigit = _ISbit(4),
  _ISspace = _ISbit(5),
  _ISprint = _ISbit(6),
  _ISgraph = _ISbit(7),
  _ISblank = _ISbit(8),
  _IScntrl = _ISbit(9),
  _ISpunct = _ISbit(10),
  _ISalnum = _ISbit(11),
};
#endif

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
