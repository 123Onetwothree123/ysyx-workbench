/*
 * NEMU 的 Berkeley SoftFloat 宿主平台配置。
 *
 * SoftFloat 上游通过 build/<platform>/platform.h 选择端序、内联策略和
 * 快速整数路径。NEMU 在 Linux 宿主上运行，因此这里使用与上游
 * Linux-x86_64-GCC 相同的配置；端序按编译器预定义判断，避免把宿主
 * 端序写死。
 *
 * 关键点是 SOFTFLOAT_FAST_INT64：NEMU 只支持 64 位宿主，打开后
 * f64 的尾数运算直接使用 uint64_t，和上游 x86_64 行为一致。
 */
#ifndef NEMU_SOFTFLOAT_PLATFORM_H
#define NEMU_SOFTFLOAT_PLATFORM_H

#if defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__)
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define LITTLEENDIAN 1
#endif
#endif

#ifndef LITTLEENDIAN
#error "NEMU SoftFloat 目前只支持小端宿主"
#endif

/* 头文件中的小工具函数必须各自生成副本，避免依赖外部 inline 定义。 */
#define INLINE static inline

#define SOFTFLOAT_FAST_INT64 1
#define SOFTFLOAT_BUILTIN_CLZ 1
#define SOFTFLOAT_INTRINSIC_INT128 1
#define INLINE_LEVEL 5
#define SOFTFLOAT_FAST_DIV32TO16 1
#define SOFTFLOAT_FAST_DIV64TO32 1

#include "opts-GCC.h"

#endif
