/* SPDX-License-Identifier: GPL-2.0 */
/*
 * 仅 clang/libc++ 路径需要：libc++ 的 std 模块不提供 C 头文件的宏
 * 与 stdio 全局量（assert、PATH_MAX、errno、va_start/va_end、
 * offsetof、strdup、stderr/stdin/stdout 等），这里统一补齐。
 * GCC 路径由 bits/stdc++.h 覆盖，无需本文件。
 */
#ifndef KCONFIG_CLANG_COMPAT_H
#define KCONFIG_CLANG_COMPAT_H

#include <cassert>
#include <cerrno>
#include <climits>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <string.h> /* strdup/strndup（POSIX） */
#include <sys/select.h>
#include <sys/stat.h>

#endif /* KCONFIG_CLANG_COMPAT_H */
