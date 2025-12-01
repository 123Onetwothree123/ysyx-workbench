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

static int cmd_w(char *args);

static int cmd_d(char *args);

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
    {"p", "Evaluate expression", cmd_p},
    {"w", "Set watchpoint for an expression", cmd_w},
    {"d", "Delete watchpoint by ID", cmd_d},
};

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
  // default to 1 step if the user is lazy and doesn't type a number
  uint64_t steps = 1;
  // did the user actually type something? Let's parse it
  if (args != NULL)
  {
    char *endptr;
    // try to convert the string to a number
    long n = strtol(args, &endptr, 10);
    // check if strtol actually found any digits
    // if args equals endptr, it means no number was found at the start
    if (args == endptr)
    {
      printf("Error: Invalid argument '%s'. Usage: si [N]\n", args);
      return 0;
    }
    // make sure the step count makes sense
    // negative steps or zero don't really work here
    if (n <= 0)
    {
      printf("Error: Steps must be a positive integer, got %ld\n", n);
      return 0;
    }
    // users might type "si 10 " (with a space), and we shouldn't crash
    while (*endptr == ' ')
      endptr++;
    // if there's still junk left after the number and spaces, complain
    if (*endptr != '\0')
    {
      printf("Error: Trailing garbage '%s' in argument\n", endptr);
      return 0;
    }
    steps = (uint64_t)n;
  }
  cpu_exec(steps);
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
  if (args == NULL || *args == '\0')
  {
    printf("nemu monitor sdb cmd_x function check args, detect args==NULL, this args need parameter input\n");
    printf("Usage: x N EXPR\n");
    printf("Example: x 10 $esp\n");
    return 0;
  }
  else if (args != NULL)
  {

    char *StringEndPointer;
    long n = strtol(args, &StringEndPointer, 10);
    // Check whether N is a valid positive integer
    if (StringEndPointer == args)
    {
      printf("Error: Missing argument N\n");
      return 0;
    }
    if ((*StringEndPointer != '\0' && *StringEndPointer != ' ') || n <= 0)
    {
      printf("Error: N must be a positive integer\n");
      return 0;
    }
    char *expr_str = StringEndPointer;
    while (*expr_str == ' ')
    {
      expr_str++;
    }
    if (*expr_str == '\0')
    {
      printf("Error: Missing expression EXPR\n");
      return 0;
    }
    bool success = false;
    sword_t addr_signed = expr(expr_str, &success);
    word_t addr = (word_t)addr_signed;
    // if fail
    if (!success)
    {
      printf("Error: Invalid expression\n");
      return 0;
    }
    // Check the validity of the memory address
    if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
    {
      printf("Error: Address 0x%08x is out of valid memory range [0x%08x, 0x%08x)\n",
             addr, CONFIG_MBASE, CONFIG_MBASE + CONFIG_MSIZE);
      return 0;
    }
    // Check if there will be any cross-border visits
    if (n == 0 || n > (CONFIG_MBASE + CONFIG_MSIZE - addr) / 4)
    {
      printf("Error: Scanning %ld words from 0x%08x would exceed memory bounds\n",
             n, addr);
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
      printf("Dec: %u\n", result);               // Dec display
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
static int cmd_w(char *args)
{
  // check NULL
  if (args == NULL || strlen(args) == 0)
  {
    printf("Error: Missing expression for watchpoint\n");
    printf("Usage: w <expression>\n");
    printf("Examples:\n");
    printf("  w $eax           - Watch register eax\n");
    printf("  w 0x80100000     - Watch memory address\n");
    printf("  w *0x80100000    - Watch dereferenced memory\n");
    return 0;
  }
  WP *wp = new_wp();
  if (wp == NULL)
  {
    printf("Error: No free watchpoint slots (maximum: %d)\n", get_max_watchpoints());
    return 0;
  }
  wp_set_expr(wp, args);
  // get exprssion default value
  bool success;
  word_t value = expr(args, &success);
  if (!success)
  {
    printf("Error: Invalid expression '%s'\n", args);
    free_wp(wp);
    return 0;
  }
  wp_set_value(wp, value);
  printf("Watchpoint %d: %s = 0x%08x\n", wp_get_no(wp), wp_get_expr(wp), value);
  return 0;
}
static int cmd_d(char *args)
{
  // check, need safe function execute
  if (args == NULL || strlen(args) == 0)
  {
    printf("Error: Missing watchpoint ID\n");
    printf("Usage: d <watchpoint_id>\n");
    printf("Example: d 1\n");
    return 0;
  }
  char *endptr;
  long id = strtol(args, &endptr, 10);
  if (*endptr != '\0')
  {
    printf("Error: Invalid watchpoint ID '%s'. ID must be a number.\n", args);
    printf("Usage: d <watchpoint_id>\n");
    return 0;
  }
  if (id < 0 || id > INT32_MAX)
  {
    printf("Error: Watchpoint ID must be between 0 and %d, but got %ld.\n", INT32_MAX, id);
    return 0;
  }
  if (!check_array_bounds((int)id, get_max_watchpoints()))
  {
    printf("Error: Watchpoint ID %ld exceeds maximum allowed ID (%d).\n", id, get_max_watchpoints() - 1);
    return 0;
  }
  WP *WpToDelete = find_wp_by_id((int)id);
  if (WpToDelete == NULL)
  {
    printf("Error: Watchpoint %ld not found\n", id);
    return 0;
  }
  free_wp(WpToDelete);
  printf("Deleted watchpoint %ld\n", id);
  return 0;
}
bool check_array_bounds(int index, int array_size)
{
  return index >= 0 && index < array_size;
}

bool check_null_pointer(const void *ptr, const char *name)
{
  if (ptr == NULL)
  {
    printf("Error: NULL pointer detected for %s\n", name);
    return false;
  }
  return true;
}
bool check_memory_address(word_t addr)
{
  if (addr == 0)
  {
    printf("Error: NULL pointer access attempt\n");
    return false;
  }
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    printf("Error: Invalid memory address " FMT_PADDR " (valid range: [" FMT_PADDR ", " FMT_PADDR "))\n",
           addr, CONFIG_MBASE, CONFIG_MBASE + CONFIG_MSIZE);
    return false;
  }
  return true;
}
bool check_string_length(const char *str, size_t max_len, const char *name)
{
  if (str == NULL)
    return false;
  size_t len = strlen(str);
  if (len >= max_len)
  {
    printf("Error: %s too long (%zu >= %zu)\n", name, len, max_len);
    return false;
  }
  return true;
}
bool is_valid_memory_region(word_t addr, size_t size)
{
  if (addr < CONFIG_MBASE || addr >= CONFIG_MBASE + CONFIG_MSIZE)
  {
    return false;
  }
  if (addr % size != 0)
  {
    return false;
  }
  if (addr + size > CONFIG_MBASE + CONFIG_MSIZE)
  {
    return false;
  }
  return true;
}