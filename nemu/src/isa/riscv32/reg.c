/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <isa.h>
#include "local-include/reg.h"
#include <cpu/cpu.h>

#include <ctype.h>
const char *regs[] = {
    "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
    "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
    "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};

// 自己写的
typedef struct
{
  char arch_name[16];   // 架构名称，x0-x31，pc
  const char *abi_name; // ABI的名称，$0，ra，sp，gp，tp，t0-t6，s0-s11，a0-a7
  const char *desc;     // 中文描述
  word_t value;         // 寄存器值
} reg_row_t;
static void reg_table_print_spaces(int count)
{
  for (int i = 0; i < count; i++)
  {
    putchar(' ');
  }
}
static const unsigned char *reg_table_skip_ansi_escape(const unsigned char *s)
{
  if (s[0] != '\033' || s[1] != '[')
  {
    return s;
  }

  s += 2;
  while (*s != '\0' && !(*s >= 0x40 && *s <= 0x7e))
  {
    s++;
  }
  if (*s != '\0')
  {
    s++;
  }
  return s;
}
static uint32_t reg_table_decode_utf8_codepoint(const unsigned char *s, int *bytes)
{
  // 1字节：0xxxxxxx
  if ((s[0] & 0x80) == 0)
  {
    *bytes = 1;
    return s[0];
  }
  // 2字节：110xxxxx 10xxxxxx
  if ((s[0] & 0xe0) == 0xc0 && s[1] != '\0' && (s[1] & 0xc0) == 0x80)
  {
    *bytes = 2;
    return ((uint32_t)(s[0] & 0x1f) << 6) | (uint32_t)(s[1] & 0x3f);
  }
  // 3字节：1110xxxx 10xxxxxx 10xxxxxx (中文在此范围)
  if ((s[0] & 0xf0) == 0xe0 &&
      s[1] != '\0' && s[2] != '\0' &&
      (s[1] & 0xc0) == 0x80 && (s[2] & 0xc0) == 0x80)
  {
    *bytes = 3;
    return ((uint32_t)(s[0] & 0x0f) << 12) |
           ((uint32_t)(s[1] & 0x3f) << 6) |
           (uint32_t)(s[2] & 0x3f);
  }
  // 4字节：11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
  if ((s[0] & 0xf8) == 0xf0 &&
      s[1] != '\0' && s[2] != '\0' && s[3] != '\0' &&
      (s[1] & 0xc0) == 0x80 &&
      (s[2] & 0xc0) == 0x80 &&
      (s[3] & 0xc0) == 0x80)
  {
    *bytes = 4;
    return ((uint32_t)(s[0] & 0x07) << 18) |
           ((uint32_t)(s[1] & 0x3f) << 12) |
           ((uint32_t)(s[2] & 0x3f) << 6) |
           (uint32_t)(s[3] & 0x3f);
  }

  *bytes = 1;
  return s[0];
}
static int reg_table_codepoint_width(uint32_t codepoint)
{
  if (codepoint == 0)
  {
    return 0;
  }

  if (codepoint < 0x20 || (codepoint >= 0x7f && codepoint < 0xa0))
  {
    return 0;
  }

  if ((codepoint >= 0x0300 && codepoint <= 0x036f) ||
      (codepoint >= 0x1ab0 && codepoint <= 0x1aff) ||
      (codepoint >= 0x1dc0 && codepoint <= 0x1dff) ||
      (codepoint >= 0x20d0 && codepoint <= 0x20ff) ||
      (codepoint >= 0xfe20 && codepoint <= 0xfe2f))
  {
    return 0;
  }

  if (codepoint >= 0x1100 &&
      (codepoint <= 0x115f ||
       codepoint == 0x2329 || codepoint == 0x232a ||
       (codepoint >= 0x2e80 && codepoint <= 0xa4cf && codepoint != 0x303f) ||
       (codepoint >= 0xac00 && codepoint <= 0xd7a3) ||
       (codepoint >= 0xf900 && codepoint <= 0xfaff) ||
       (codepoint >= 0xfe10 && codepoint <= 0xfe19) ||
       (codepoint >= 0xfe30 && codepoint <= 0xfe6f) ||
       (codepoint >= 0xff00 && codepoint <= 0xff60) ||
       (codepoint >= 0xffe0 && codepoint <= 0xffe6) ||
       (codepoint >= 0x20000 && codepoint <= 0x2fffd) ||
       (codepoint >= 0x30000 && codepoint <= 0x3fffd)))
  {
    return 2;
  }

  return 1;
}
static int reg_table_display_width(const char *s)
{
  int width = 0;
  const unsigned char *p = (const unsigned char *)s;

  while (*p != '\0')
  {
    if (*p == '\033' && p[1] == '[')
    {
      p = reg_table_skip_ansi_escape(p);
      continue;
    }

    int bytes = 1;
    uint32_t codepoint = reg_table_decode_utf8_codepoint(p, &bytes);
    width += reg_table_codepoint_width(codepoint);
    p += bytes;
  }

  return width;
}
static void reg_table_print_border(const int *widths, int nr_cols)
{
  fputs(ANSI_FG_BLUE, stdout);
  putchar('+');
  for (int col = 0; col < nr_cols; col++)
  {
    for (int i = 0; i < widths[col] + 2; i++)
    {
      putchar('-');
    }
    putchar('+');
  }
  fputs(ANSI_NONE, stdout);
  putchar('\n');
}
static void reg_table_print_cell(const char *text, int width, bool right_align)
{
  int padding = width - reg_table_display_width(text);
  if (padding < 0)
  {
    padding = 0;
  }

  putchar(' ');
  if (right_align)
  {
    reg_table_print_spaces(padding);
  }

  fputs(text, stdout);

  if (!right_align)
  {
    reg_table_print_spaces(padding);
  }

  printf(" " ANSI_FG_BLUE "|" ANSI_NONE);
}

static const char *get_reg_desc(const char *arch_name, const char *abi_name)
{
  if (strcmp(arch_name, "pc") == 0)
    return "程序计数器";
  if (strcmp(arch_name, "x0") == 0)
    return "零寄存器";
  if (strcmp(abi_name, "ra") == 0)
    return "返回地址";
  if (strcmp(abi_name, "sp") == 0)
    return "栈指针";
  if (strcmp(abi_name, "gp") == 0)
    return "全局指针";
  if (strcmp(abi_name, "tp") == 0)
    return "线程指针";
  if (abi_name[0] == 'a')
    return "参数寄存器";
  if (abi_name[0] == 's')
    return "保存寄存器";
  if (abi_name[0] == 't')
    return "临时寄存器";
  return "通用寄存器";
}

static const char *get_reg_header_color(const char *title)
{
  if (strcmp(title, "编号") == 0)
    return ANSI_FG_CYAN;
  if (strcmp(title, "寄存器") == 0)
    return ANSI_FG_BLUE;
  if (strcmp(title, "十进制") == 0)
    return ANSI_FG_WHITE;
  if (strcmp(title, "十六进制") == 0)
    return ANSI_FG_GREEN;
  if (strcmp(title, "说明") == 0)
    return ANSI_FG_YELLOW;
  return ANSI_FG_WHITE;
}

static const char *get_reg_row_color(const char *arch_name, const char *abi_name)
{
  if (strcmp(arch_name, "pc") == 0)
    return ANSI_FG_YELLOW;
  if (strcmp(arch_name, "x0") == 0 || strcmp(abi_name, "$0") == 0)
    return ANSI_FG_WHITE;
  if (strcmp(abi_name, "ra") == 0)
    return ANSI_FG_CYAN;
  if (strcmp(abi_name, "sp") == 0)
    return ANSI_FG_YELLOW;
  if (strcmp(abi_name, "gp") == 0)
    return ANSI_FG_BLUE;
  if (strcmp(abi_name, "tp") == 0)
    return ANSI_FG_MAGENTA;
  if (abi_name[0] == 'a')
    return ANSI_FG_GREEN;
  if (abi_name[0] == 's')
    return ANSI_FG_CYAN;
  if (abi_name[0] == 't')
    return ANSI_FG_MAGENTA;
  return ANSI_FG_WHITE;
}

static void fill_reg_row_info(int row_index, reg_row_t *row)
{
  if (row_index == 0)
  {
    snprintf(row->arch_name, sizeof(row->arch_name), "%s", "pc");
    row->abi_name = "pc";
    row->value = cpu.pc;
  }
  else
  {
    snprintf(row->arch_name, sizeof(row->arch_name), "x%d", row_index - 1);
    row->abi_name = regs[row_index - 1];
    row->value = cpu.gpr[row_index - 1];
  }

  row->desc = get_reg_desc(row->arch_name, row->abi_name);
}
void isa_reg_display()
{
  const int nr_gpr = (int)(sizeof(regs) / sizeof(regs[0]));
  const int nr_rows = nr_gpr + 1;

  int id_width = reg_table_display_width("编号");
  int name_width = reg_table_display_width("寄存器");
  int dec_width = reg_table_display_width("十进制");
  int hex_width = reg_table_display_width("十六进制");
  int desc_width = reg_table_display_width("说明");

  for (int i = 0; i < nr_rows; i++)
  {
    reg_row_t row;
    char dec_plain[32];
    char hex_plain[32];

    fill_reg_row_info(i, &row);
    snprintf(dec_plain, sizeof(dec_plain), "%" PRIuMAX, (uintmax_t)row.value);
    snprintf(hex_plain, sizeof(hex_plain), FMT_WORD, row.value);

    if (reg_table_display_width(row.arch_name) > id_width)
    {
      id_width = reg_table_display_width(row.arch_name);
    }
    if (reg_table_display_width(row.abi_name) > name_width)
    {
      name_width = reg_table_display_width(row.abi_name);
    }
    if (reg_table_display_width(dec_plain) > dec_width)
    {
      dec_width = reg_table_display_width(dec_plain);
    }
    if (reg_table_display_width(hex_plain) > hex_width)
    {
      hex_width = reg_table_display_width(hex_plain);
    }
    if (reg_table_display_width(row.desc) > desc_width)
    {
      desc_width = reg_table_display_width(row.desc);
    }
  }

  int col_widths[] = {id_width, name_width, dec_width, hex_width, desc_width};
  const int nr_cols = (int)(sizeof(col_widths) / sizeof(col_widths[0]));

  char id_header[32], name_header[32], dec_header[32], hex_header[32], desc_header[32];
  snprintf(id_header, sizeof(id_header), "%s编号%s", get_reg_header_color("编号"), ANSI_NONE);
  snprintf(name_header, sizeof(name_header), "%s寄存器%s", get_reg_header_color("寄存器"), ANSI_NONE);
  snprintf(dec_header, sizeof(dec_header), "%s十进制%s", get_reg_header_color("十进制"), ANSI_NONE);
  snprintf(hex_header, sizeof(hex_header), "%s十六进制%s", get_reg_header_color("十六进制"), ANSI_NONE);
  snprintf(desc_header, sizeof(desc_header), "%s说明%s", get_reg_header_color("说明"), ANSI_NONE);

  printf("寄存器状态：\n");
  reg_table_print_border(col_widths, nr_cols);
  fputs(ANSI_FG_BLUE "|" ANSI_NONE, stdout);
  reg_table_print_cell(id_header, id_width, true);
  reg_table_print_cell(name_header, name_width, false);
  reg_table_print_cell(dec_header, dec_width, true);
  reg_table_print_cell(hex_header, hex_width, true);
  reg_table_print_cell(desc_header, desc_width, false);
  putchar('\n');
  reg_table_print_border(col_widths, nr_cols);

  for (int i = 0; i < nr_rows; i++)
  {
    reg_row_t row;
    const char *row_color;
    char id_str[32], name_str[32], dec_str[64], hex_str[64], desc_str[64];

    fill_reg_row_info(i, &row);
    row_color = get_reg_row_color(row.arch_name, row.abi_name);

    snprintf(id_str, sizeof(id_str), "%s%s%s", row_color, row.arch_name, ANSI_NONE);
    snprintf(name_str, sizeof(name_str), "%s%s%s", row_color, row.abi_name, ANSI_NONE);
    snprintf(dec_str, sizeof(dec_str), ANSI_FG_WHITE "%" PRIuMAX ANSI_NONE, (uintmax_t)row.value);
    snprintf(hex_str, sizeof(hex_str), "%s" FMT_WORD ANSI_NONE, row_color, row.value);
    snprintf(desc_str, sizeof(desc_str), "%s%s%s", row_color, row.desc, ANSI_NONE);

    fputs(ANSI_FG_BLUE "|" ANSI_NONE, stdout);
    reg_table_print_cell(id_str, id_width, true);
    reg_table_print_cell(name_str, name_width, false);
    reg_table_print_cell(dec_str, dec_width, true);
    reg_table_print_cell(hex_str, hex_width, true);
    reg_table_print_cell(desc_str, desc_width, false);
    putchar('\n');
    reg_table_print_border(col_widths, nr_cols);
  }
}

word_t isa_reg_str2val(const char *s, bool *success)
{
  // 如果是空，直接就转换失败
  if (s == NULL || success == NULL)
  {
    if (success)
    {
      *success = false;
    }
    return 0;
  }
  *success = false;
  // 检测到是$，就直接开始迭代s变量
  if (*s == '$')
  {
    s++;
  }
  // 末尾
  if (*s == '\0')
  {
    return 0;
  }
  // 如果检测到命令要求输出PC寄存器，就直接返回目前的PC值
  if (strcmp(s, "pc") == 0)
  {
    *success = true;
    return cpu.pc;
  }
  for (const char *p = s; *p != '\0'; p++)
  {
    if (!isalnum((unsigned char)*p))
    {
      return 0; // 检查到非法字符，直接返回0（失败）
    }
  }
  int num_regs = sizeof(regs) / sizeof(regs[0]);
  if (num_regs > sizeof(cpu.gpr) / sizeof(cpu.gpr[0]))
  {
    printf("错误：寄存器数组大小不匹配\n");
    if (success)
    {
      *success = false;
    }
    return 0;
  }
  // 直接遍历
  for (int i = 0; i < num_regs; i++)
  {
    // 从寄存器组里面取出名字
    const char *reg_name_in_array = regs[i];
    if (reg_name_in_array[0] == '$')
    {
      reg_name_in_array++; // 跳过$字符
    }
    // 检测输入的数据是否和寄存器组里面的名字匹配
    if (strcmp(s, reg_name_in_array) == 0)
    {
      if (i >= 0 && i < sizeof(cpu.gpr) / sizeof(cpu.gpr[0]))
      {
        *success = true;
        return cpu.gpr[i]; // 返回寄存器中存储的数据
      }
      else
      {
        printf("错误：寄存器索引 %d 越界\n", i);
        *success = false;
        return 0;
      }
    }
  }
  return 0;
}

isa_reg_set_result_t isa_reg_setval(const char *s, word_t val)
{
  if (s == NULL)
  {
    return ISA_REG_SET_INVALID;
  }
  if (*s == '$')
  {
    s++;
  }
  if (*s == '\0')
  {
    return ISA_REG_SET_INVALID;
  }
  if (strcmp(s, "pc") == 0)
  {
    // 本来打算直接设计的后来发现pc不在regs[]里面，所以得单独拉出来设计
    cpu.pc = val;
    return ISA_REG_SET_SUCCESS;
  }
  for (const char *p = s; *p != '\0'; p++)
  {
    // 拿来过滤非法字符
    if (!isalnum((unsigned char)*p))
    {
      return ISA_REG_SET_INVALID;
    }
  }
  int num_regs = sizeof(regs) / sizeof(regs[0]); // 算数组长度
  for (int i = 0; i < num_regs; i++)             // 实在是实现不出来高级的设计了，只能用遍历全部的方式来实现功能了
  {
    const char *reg_name_in_array = regs[i]; // 通过i获取对应的寄存器的索引的名称的字符串
    if (reg_name_in_array[0] == '$')
    {
      reg_name_in_array++; // 这是为了迭代指针，后移一个字符
    }
    if (strcmp(s, reg_name_in_array) == 0)
    {
      if (i == 0)
      {
        cpu.gpr[0] = 0; // 根据手册，0号寄存器不让动，不得写入
        return ISA_REG_SET_READONLY;
      }
      cpu.gpr[i] = val; // 直接写入值
      return ISA_REG_SET_SUCCESS;
    }
  }
  return ISA_REG_SET_INVALID; // 其他情况直接返回invalid无效
}