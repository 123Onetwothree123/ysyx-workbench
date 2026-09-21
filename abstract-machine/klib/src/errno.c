#include <am.h>
#include <errno.h>

#if !defined(__ISA_NATIVE__)
int errno;
#endif
