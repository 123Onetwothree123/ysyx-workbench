/*
 * KLIB build configuration for Berkeley SoftFloat Release 3e.
 *
 * The AM targets supported by this copy are little-endian today.  Keep the
 * conditional form so the unmodified SoftFloat sources retain their normal
 * big-endian layout if a big-endian target is added later.
 */
#ifndef KLIB_SOFTFLOAT_PLATFORM_H
#define KLIB_SOFTFLOAT_PLATFORM_H

#if defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define LITTLEENDIAN 1
#endif

/* These helpers live in headers and must not create external definitions. */
#define INLINE static inline

/* GCC/Clang lower these to KLIB's __clzsi2/__clzdi2 on RV32. */
#define SOFTFLOAT_BUILTIN_CLZ 1
#include "opts-GCC.h"

/*
 * Deliberately do not define SOFTFLOAT_FAST_INT64.  RV32 has no native
 * 64-bit integer registers.  THREAD_LOCAL is also deliberately absent:
 * Abstract Machine has no TLS runtime, so SoftFloat's state is process-global.
 */

#endif
