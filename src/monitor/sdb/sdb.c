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
#include <cpu/cpu.h>
#include <readline/readline.h>
#include <readline/history.h>
#include "sdb.h"
#include "../../../src/isa/riscv32/local-include/reg.h"
#include <memory/paddr.h>

static int is_batch_mode = false;

void init_regex();
void init_wp_pool();

/* We use the `readline' library to provide more flexibility to read from stdin. */
static char *rl_gets()
{
  static char *line_read = NULL;

  if (line_read)
  {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(nemu) ");

  if (line_read && *line_read)
  {
    add_history(line_read);
  }

  return line_read;
}

static int cmd_c(char *args)
{
  cpu_exec(-1);
  return 0;
}

static int cmd_q(char *args)
{
  return -1;
}

static int cmd_help(char *args);

static int cmd_si(char *args);

static int cmd_info(char *args);

static int cmd_x(char *args);

static int cmd_p(char *args);

static struct
{
  const char *name;
  const char *description;
  int (*handler)(char *);
} cmd_table[] = {
    {"help", "Display information about all supported commands", cmd_help},
    {"c", "Continue the execution of the program", cmd_c},
    {"q", "Exit NEMU", cmd_q},

    /* TODO: Add more commands */
    {"si", "Single step execution [N], default Single step execution", cmd_si},
    {"info", "Print program status", cmd_info},
    {"x", "Examine memory", cmd_x},
    {"p", "Evaluate expression", cmd_p}};

#define NR_CMD ARRLEN(cmd_table)

static int cmd_help(char *args)
{
  /* extract the first argument */
  char *arg = strtok(NULL, " ");
  int i;

  if (arg == NULL)
  {
    /* no argument given */
    for (i = 0; i < NR_CMD; i++)
    {
      printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
    }
  }
  else
  {
    for (i = 0; i < NR_CMD; i++)
    {
      if (strcmp(arg, cmd_table[i].name) == 0)
      {
        printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
        return 0;
      }
    }
    printf("Unknown command '%s'\n", arg);
  }
  return 0;
}

void sdb_set_batch_mode()
{
  is_batch_mode = true;
}

void sdb_mainloop()
{
  if (is_batch_mode)
  {
    cmd_c(NULL);
    return;
  }

  for (char *str; (str = rl_gets()) != NULL;)
  {
    char *str_end = str + strlen(str);

    /* extract the first token as the command */
    char *cmd = strtok(str, " ");
    if (cmd == NULL)
    {
      continue;
    }

    /* treat the remaining string as the arguments,
     * which may need further parsing
     */
    char *args = cmd + strlen(cmd) + 1;
    if (args >= str_end)
    {
      args = NULL;
    }

#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

    int i;
    for (i = 0; i < NR_CMD; i++)
    {
      if (strcmp(cmd, cmd_table[i].name) == 0)
      {
        if (cmd_table[i].handler(args) < 0)
        {
          return;
        }
        break;
      }
    }

    if (i == NR_CMD)
    {
      printf("Unknown command '%s'\n", cmd);
    }
  }
}

void init_sdb()
{
  /* Compile the regular expressions. */
  init_regex();

  /* Initialize the watchpoint pool. */
  init_wp_pool();
}

static int cmd_si(char *args)
{
  // int steps = 1;//default step=1 execute si.
  int steps;
  if (args == NULL)
  {
    printf("monitor cmd_si arg==NULL, so default step=1, executed one\n");
    steps = 1;
    return 0;
  }
  else if (args != NULL) // check args is not null, it have value.
  {
    char *StorageEndAddressPointer;
    long Int32BitStoreTemporaryStep = strtol(args, &StorageEndAddressPointer, 10); // because this nemu default RISC-V 32bit
    // because need all direct pass check, can not single check or a large number of nested if statements, so write if.
    if (*StorageEndAddressPointer != '\0') // check string is a pure number
    {
      printf("Error: Invalid argument '%s' for 'si'. Argument must be a positive integer.\n", args);
      printf("I think should usage: si [N]\n");
      return 0; // can not return -1, need to return 0 and do nothing.
    }
    if (Int32BitStoreTemporaryStep > INT32_MAX || Int32BitStoreTemporaryStep < INT32_MIN) // nemu default RISC-V 32bit
    {
      printf("Error: Argument for 'si' must be a positive integer, but got %ld.\n", Int32BitStoreTemporaryStep);
      return 0;
    }
    steps = (int)Int32BitStoreTemporaryStep;
    cpu_exec(steps);
    return 0;
  }
  else
  {
    printf("I do not know monitor cmd_si executing happened something.if and else if do not run\n");
    return -1;
  }
  return 0;
}
static int cmd_info(char *args)
{
  if (args == NULL)
  {
    printf("nemu monitor sdb cmd_info function check args, detect args==NULL, this args need parameter input\n");
    printf("  r - print register status\n");
    printf("  w - print watchpoint information\n");
    return 0;
  }
  else if (args != NULL)
  {
    while (*args == ' ')
      args++;
    if (strcmp(args, "r") == 0) // Subcommand 'r': Print register status
    {
      isa_reg_display();
    }
    else if (strcmp(args, "w") == 0) // Subcommand 'w': Print monitor point information
    {
      PrintWatchPoint();
    }
    else
    {
      printf("Unknown info subcommand: %s\n", args);
      printf("Supported subcommands: r, w\n");
      printf("cmd_info function args happend unknow something.\n");
    }
    return 0;
  }
  else
  {
    printf("I do not know monitor cmd_info executing happened something.if and else if do not run\n");
    return -1;
  }
  return 0;
}
static int cmd_x(char *args)
{
  if (args == NULL)
  {
    printf("nemu monitor sdb cmd_x function check args, detect args==NULL, this args need parameter input\n");
    printf("Usage: x N EXPR\n");
    printf("Example: x 10 $esp\n");
    return 0;
  }
  else if (args != NULL)
  {
    char *N_str = strtok(args, " "); // check args null string, return the first split substring.
    if (N_str == NULL)
    {
      printf("Error: Missing argument N\n");
      return 0;
    }
    char *StringEndPointer;
    long n = strtol(N_str, &StringEndPointer, 10);
    // Check whether N is a valid positive integer
    if (*StringEndPointer != '\0' || n <= 0)
    {
      printf("Error: N must be a positive integer\n");
      return 0;
    }
    char *expr_str = strtok(NULL, " ");
    if (expr_str == NULL)
    {
      printf("Error: Missing expression EXPR\n");
      return 0;
    }
    bool success = true;
    sword_t addr_signed = expr(expr_str, &success);
    word_t addr = (word_t)addr_signed;
    // if fail
    if (!success)
    {
      printf("Error: Invalid expression\n");
      return 0;
    }
    // print
    printf("Scanning %ld words from address 0x%08x:\n", n, addr);
    for (int i = 0; i < n; i++)
    {
      word_t data = paddr_read(addr + i * 4, 4); // read 4 byte
      printf("0x%08x: 0x%08x\n", addr + i * 4, data);
    }
  }
  else
  {
    printf("I do not know monitor cmd_x executing happened something.if and else if do not run\n");
    return -1;
  }
  return 0;
}
static int cmd_p(char *args)
{
  if (args == NULL)
  {
    printf("cmd_p checked args==NULL, need input parameter.\n");
    printf("Usage: p EXPR\n");
    printf("Example: p 5 + 4 * 3 / 2 - 1\n");
    return 0;
  }
  else if (args != NULL)
  {
    bool success = true;
    word_t result = expr(args, &success);

    if (success)
    {
      printf("Dec: %u\n", result);     // Dec display
      printf("Hex: 0x%08x\n", (uint32_t)result); // Hex display
      if (result < 0)
    {
      printf("Unsigned: %u\n", (uint32_t)result);
    }
    }
    else
    {
      printf("Expression evaluation failed!\n");
    }
    return 0;
  }
  else
  {
    printf("cmd_p args unknow error.\n");
    return 0;
  }
}