/* SPDX-License-Identifier: GPL-2.0 */
/*
 * 解析/词法入口声明：这些函数定义在 bison/flex 生成的文件里，
 * 生成文件不是 kconfig.core 的模块单元，因此单独放在这个头文件中。
 */
#ifndef KCONFIG_PARSE_H
#define KCONFIG_PARSE_H

#include <cstdio> /* FILE */

namespace kconfig {

/* parser.y（生成） */
extern int cdebug;
void conf_parse(const char *name);
void zconf_error(const char *err, ...);
void zconfdump(FILE *out);

/* lexer.l（生成） */
int yylex(void);
void zconf_starthelp(void);
FILE *zconf_fopen(const char *name);
void zconf_initscan(const char *name);
void zconf_nextfile(const char *name);
int zconf_lineno(void);
const char *zconf_curname(void);

} /* namespace kconfig */

/* flex 的 %option yylineno 在全局作用域定义 yylineno */
extern int yylineno;

#endif /* KCONFIG_PARSE_H */
