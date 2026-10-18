#!/bin/bash
# 生成文件（bison）：非模块单元，定义 kconfig_parse.hpp 里的解析入口；
# 顶部注入核心模块导入。
# GCC 下 std 由 -include std_compat.h 先提供（include 在前、import 在后）；
# clang 下用 import std。
cat > "$2" <<'EOF'
/*
 * 生成文件（bison）：导入 kconfig.core 模块。
 */
#ifdef __clang__
import std;
import kconfig.core;
#else
import kconfig.core;
#endif
#ifdef __clang__
#include "kconfig_clang_compat.h"
#endif
#include "kconfig_parse.hpp"

EOF
cat "$1" >> "$2"
