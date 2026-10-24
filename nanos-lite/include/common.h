#ifndef __COMMON_H__
#define __COMMON_H__

/* Uncomment these macros to enable corresponding functionality. */
#define HAS_CTE
#define HAS_VME
/* PA4.4：多道程序 + 基于时钟中断的抢占式分时。 */
#define MULTIPROGRAM
#define TIME_SHARING

#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <debug.h>

#endif
