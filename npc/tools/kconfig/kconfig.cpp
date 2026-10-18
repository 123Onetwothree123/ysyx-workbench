module;
#ifdef __clang__
#else
#include <bits/stdc++.h>
#endif
#include "kconfig_parse.hpp"   /* conf_parse */
#ifdef __clang__
#include "kconfig_clang_compat.h"
#endif
module kconfig.core;
#ifdef __clang__
import std;
#endif
using namespace std;

// SPDX-License-Identifier: GPL-2.0
/*
 * Kconfig 门面：全局唯一实例的定义与高层入口。
 */


namespace kconfig {

/* 一次配置处理的全部状态（取代原先散落的文件级静态单例） */
struct Kconfig kconf;

void Kconfig::parse(const char *kconfig_file)
{
	conf_parse(kconfig_file);
}

int Kconfig::read(const char *name)
{
	return conf_read(name);
}

int Kconfig::write(const char *name)
{
	return conf_write(name);
}

} /* namespace kconfig */
