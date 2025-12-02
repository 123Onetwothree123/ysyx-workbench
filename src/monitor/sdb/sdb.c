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

MemoryRegion g_memory_regions[MAX_MEMORY_REGIONS];
int g_region_count = 0;

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
    printf("Usage: x N[b|h|w] EXPR\n");
    printf("  N: Number of items to examine\n");
    printf("  [b|h|w]: Optional unit size (default w):\n");
    printf("    b - byte (1 byte)\n");
    printf("    h - half-word (2 bytes)\n");
    printf("    w - word (4 bytes)\n");
    printf("  EXPR: Expression evaluating to the start address\n");
    printf("Example: x 10h $esp  (Examine 10 half-words from stack pointer)\n");
    printf("Example: x 16b 0x80100000 (Examine 16 bytes from 0x80100000)\n");
    return 0;
  }
  char *StringEndPointer;
  long n = strtol(args, &StringEndPointer, 10);
  if (StringEndPointer == args)
  {
    printf("Error: Missing argument N\n");
    return 0;
  }
  if (n <= 0)
  {
    printf("Error: N must be a positive integer\n");
    return 0;
  }
  char *expr_str = StringEndPointer;
  size_t unit_size = 4;
  while (*expr_str == ' ') { expr_str++; }
  switch (*expr_str)
  {
    case 'b': unit_size = 1; expr_str++; break;
    case 'h': unit_size = 2; expr_str++; break;
    case 'w': unit_size = 4; expr_str++; break;
    default:
      break;
  }
  while (*expr_str == ' ') { expr_str++; }

  if (*expr_str == '\0')
  {
    printf("Error: Missing expression EXPR\n");
    return 0;
  }
  bool success = false;
  sword_t addr_signed = expr(expr_str, &success);
  if (!success)
  {
    printf("Error: Invalid expression: %s\n", expr_get_error_msg());
    return 0;
  }
  word_t addr = (word_t)addr_signed;
  printf("Scanning %ld items (unit size: %zu byte%s) from address 0x%08x:\n",
         n, unit_size, (unit_size > 1 ? "s" : ""), addr);
  word_t data;
  for (int i = 0; i < n; i++)
  {
    word_t current_addr = addr + i * unit_size;
    if (!safe_paddr_read(current_addr, &data, unit_size))
    {
      printf("Error: Failed to read memory at 0x%08x (scan stopped)\n", current_addr);
      return 0;
    }
    printf("0x%08x: ", current_addr);
    switch (unit_size)
    {
      case 1: printf("0x%02x\n", (uint8_t)data); break;
      case 2: printf("0x%04x\n", (uint16_t)data); break;
      case 4: printf("0x%08x\n", (uint32_t)data); break;
      default: printf("0x%08x\n", data);
    }
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
    sword_t result_signed = expr(args, &success);
    word_t result_unsigned = (word_t)result_signed;

    if (success)
    {
      printf("Signed (Dec):   %d\n", result_signed);
      printf("Unsigned (Dec): %u\n", result_unsigned);
      printf("Hex:            0x%08x\n", result_unsigned);
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
  if (!validate_expression_memory_access_flexible(args))
  {
    printf("Error: Expression contains invalid memory access\n");
    free_wp(wp);
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
void auto_configure_memory_regions(void)
{
  // clear now config
  memset(g_memory_regions, 0, sizeof(g_memory_regions));
  g_region_count = 0;

  printf("Auto-configuring memory regions for ISA: %s\n",
#ifdef CONFIG_ISA_riscv32
         "RISC-V 32-bit"
#elif defined(CONFIG_ISA_riscv64)
         "RISC-V 64-bit"
#elif defined(CONFIG_ISA_x86)
         "x86"
#elif defined(CONFIG_ISA_mips32)
         "MIPS32"
#else
         "Unknown"
#endif
  );
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    g_memory_regions[g_region_count].start = CONFIG_MBASE;
    g_memory_regions[g_region_count].end = CONFIG_MBASE + CONFIG_MSIZE;
    g_memory_regions[g_region_count].type = MEM_TYPE_RAM;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = true;
    g_memory_regions[g_region_count].name = "Main Memory";
    g_memory_regions[g_region_count].description = "System main memory";
    g_region_count++;

    printf("  Added RAM: [0x%08x - 0x%08x] Size: %u MB\n",
           CONFIG_MBASE, CONFIG_MBASE + CONFIG_MSIZE,
           CONFIG_MSIZE / (1024 * 1024));
  }
#ifdef CONFIG_ISA_riscv32
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    g_memory_regions[g_region_count].start = 0x02000000;
    g_memory_regions[g_region_count].end = 0x02010000;
    g_memory_regions[g_region_count].type = MEM_TYPE_MMIO;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "CLINT";
    g_memory_regions[g_region_count].description = "Core Local Interruptor";
    g_region_count++;
  }
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    // Platform Level Interrupt Controller
    g_memory_regions[g_region_count].start = 0x0C000000;
    g_memory_regions[g_region_count].end = 0x10000000;
    g_memory_regions[g_region_count].type = MEM_TYPE_MMIO;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "PLIC";
    g_memory_regions[g_region_count].description = "Platform Level Interrupt Controller";
    g_region_count++;
  }
#endif
#ifdef CONFIG_ISA_x86
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    // VGA GPU memory
    g_memory_regions[g_region_count].start = 0x000A0000;
    g_memory_regions[g_region_count].end = 0x000C0000;
    g_memory_regions[g_region_count].type = MEM_TYPE_MMIO;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "VGA Memory";
    g_memory_regions[g_region_count].description = "VGA display memory";
    g_region_count++;
  }
#endif
  // Add device memory regions based on device configuration
#ifdef CONFIG_HAS_SERIAL
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    g_memory_regions[g_region_count].start = CONFIG_SERIAL_MMIO;
    g_memory_regions[g_region_count].end = CONFIG_SERIAL_MMIO + 0x1000;
    g_memory_regions[g_region_count].type = MEM_TYPE_MMIO;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "Serial Port";
    g_memory_regions[g_region_count].description = "Serial controller MMIO";
    g_region_count++;
  }
#endif
#ifdef CONFIG_HAS_TIMER
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    g_memory_regions[g_region_count].start = CONFIG_TIMER_MMIO;
    g_memory_regions[g_region_count].end = CONFIG_TIMER_MMIO + 0x1000;
    g_memory_regions[g_region_count].type = MEM_TYPE_MMIO;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "Timer";
    g_memory_regions[g_region_count].description = "Timer MMIO";
    g_region_count++;
  }
#endif
#ifdef CONFIG_HAS_VGA
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    g_memory_regions[g_region_count].start = CONFIG_VGA_MMIO;
    g_memory_regions[g_region_count].end = CONFIG_VGA_MMIO + 0x10000;
    g_memory_regions[g_region_count].type = MEM_TYPE_MMIO;
    g_memory_regions[g_region_count].is_readable = true;
    g_memory_regions[g_region_count].is_writable = true;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "VGA Ctrl";
    g_memory_regions[g_region_count].description = "VGA controller MMIO";
    g_region_count++;
  }
#endif
  if (g_region_count < MAX_MEMORY_REGIONS)
  {
    // NULL pointer
    g_memory_regions[g_region_count].start = 0x0;
    g_memory_regions[g_region_count].end = 0x1000;
    g_memory_regions[g_region_count].type = MEM_TYPE_RESERVED;
    g_memory_regions[g_region_count].is_readable = false;
    g_memory_regions[g_region_count].is_writable = false;
    g_memory_regions[g_region_count].is_executable = false;
    g_memory_regions[g_region_count].name = "NULL Pointer";
    g_memory_regions[g_region_count].description = "NULL pointer protection zone";
    g_region_count++;
  }
  printf("Auto-configuration complete: %d memory regions defined\n", g_region_count);
}
const MemoryRegion *query_memory_region(word_t addr)
{
  for (int i = 0; i < g_region_count; i++)
  {
    if (addr >= g_memory_regions[i].start && addr < g_memory_regions[i].end)
    {
      return &g_memory_regions[i];
    }
  }
  return NULL;
}
bool validate_address_flexible(word_t addr, size_t size, bool is_write)
{
  // Auto-configuration (on first call)
  static bool initialized = false;
  if (!initialized)
  {
    auto_configure_memory_regions();
    initialized = true;
  }
  // Check address alignment
  if (addr % size != 0)
  {
    printf("Warning: Unaligned access at 0x%08x (size %zu)\n", addr, size);
    // Some architectures support unaligned access, only warn here
  }
  // Query memory region
  const MemoryRegion *region = query_memory_region(addr);
  if (region == NULL)
  {
    printf("Error: Address 0x%08x not in any configured memory region\n", addr);
    return false;
  }
  // Check if address range is fully within the region
  if (addr + size > region->end)
  {
    printf("Error: Access of size %zu at 0x%08x crosses region boundary\n",
           size, addr);
    return false;
  }
  // Check permissions
  if (!region->is_readable)
  {
    printf("Error: Address 0x%08x is in non-readable region '%s'\n",
           addr, region->name);
    return false;
  }
  if (is_write && !region->is_writable)
  {
    printf("Error: Address 0x%08x is in read-only region '%s'\n",
           addr, region->name);
    return false;
  }
  // Special handling for MMIO regions
  if (region->type == MEM_TYPE_MMIO)
  {
    // MMIO regions typically have strict access size requirements
    if (size != 1 && size != 2 && size != 4 && size != 8)
    {
      printf("Error: Invalid access size %zu for MMIO region '%s'\n",
             size, region->name);
      return false;
    }
    // Check if aligned to the correct size
    if (addr % size != 0)
    {
      printf("Error: MMIO region '%s' requires %zu-byte alignment\n",
             region->name, size);
      return false;
    }
  }
  // For RAM regions, attempt actual access validation
  if (region->type == MEM_TYPE_RAM)
  {
    word_t test_value;
    if (!safe_paddr_read(addr, &test_value, size))
    {
      printf("Error: Failed to safely read from address 0x%08x\n", addr);
      return false;
    }
  }
  return true;
}
bool validate_expression_memory_access_flexible(const char *expression)
{
  if (expression == NULL || strlen(expression) == 0)
  {
    printf("Error: Empty expression\n");
    return false;
  }
  printf("Validating expression with flexible memory configuration: %s\n", expression);
  static bool initialized = false;
  if (!initialized)
  {
    auto_configure_memory_regions();
    initialized = true;
  }
  printf("Current memory layout:\n");
  for (int i = 0; i < g_region_count; i++)
  {
    printf("  [%d] %s: 0x%08x-0x%08x (%s)\n",
           i,
           g_memory_regions[i].name,
           g_memory_regions[i].start,
           g_memory_regions[i].end,
           g_memory_regions[i].description);
  }
  int depth = 0;
  const char *ptr = expression;

  while (*ptr && depth < 3)
  {
    if (*ptr == '*')
    {
      if (strncmp(ptr, "*0x", 3) == 0)
      {
        char addr_str[32];
        int i = 0;
        ptr += 2;
        while (isxdigit(*ptr) && i < sizeof(addr_str) - 1)
        {
          addr_str[i++] = *ptr++;
        }
        addr_str[i] = '\0';
        word_t addr = (word_t)strtol(addr_str, NULL, 16);
        if (!validate_address_flexible(addr, sizeof(word_t), false))
        {
          return false;
        }
      }
    }
    ptr++;
  }
  bool success = false;
  sword_t result = expr((char *)expression, &success);
  (void)result; // Intentionally not used, because -Werror
  if (!success)
  {
    printf("Error: Expression evaluation failed\n");
    return false;
  }
  printf("Expression validation passed with flexible configuration\n");
  return true;
}
bool add_custom_memory_region(word_t start, word_t end, MemoryType type,
                              bool readable, bool writable, bool executable,
                              const char *name, const char *description)
{
  if (g_region_count >= MAX_MEMORY_REGIONS)
  {
    printf("Error: Maximum memory regions (%d) exceeded\n", MAX_MEMORY_REGIONS);
    return false;
  }
  if (start >= end)
  {
    printf("Error: Invalid region range [0x%08x, 0x%08x)\n", start, end);
    return false;
  }
  for (int i = 0; i < g_region_count; i++)
  {
    if (!(end <= g_memory_regions[i].start || start >= g_memory_regions[i].end))
    {
      printf("Error: Region overlaps with existing region '%s'\n", g_memory_regions[i].name);
      return false;
    }
  }
  g_memory_regions[g_region_count].start = start;
  g_memory_regions[g_region_count].end = end;
  g_memory_regions[g_region_count].type = type;
  g_memory_regions[g_region_count].is_readable = readable;
  g_memory_regions[g_region_count].is_writable = writable;
  g_memory_regions[g_region_count].is_executable = executable;
  g_memory_regions[g_region_count].name = name;
  g_memory_regions[g_region_count].description = description;
  g_region_count++;
  printf("Added custom region: %s [0x%08x-0x%08x]\n", name, start, end);
  return true;
}