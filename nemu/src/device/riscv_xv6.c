/***************************************************************************************
 * xv6-riscv 参考平台设备
 *
 * 该文件只实现 xv6（以及 direct-NPC）实际使用的最小设备集合：
 *   - 0x02000000 CLINT：mtime / mtimecmp
 *   - 0x0c000000 PLIC ：priority / pending / enable / threshold / claim
 *   - 0x10000000 16550 UART：THR/RBR、IER、IIR/FCR、LCR、MCR、LSR、MSR、SCR
 *
 * 物理地址与 xv6-riscv/kernel/memlayout.h 以及 NPC 的 PMA 保持一致。
 * NEMU 是单 hart 模型，因此 PLIC 只有一个 enable/threshold/claim 组；
 * 同时接受标准 S 上下文地址（0x2080/0x201000/0x201004）和 NPC 早期
 * 使用的 context 0 地址（0x2000/0x200000/0x200004）。
 *
 * 设备行为对 DiffTest 的影响：
 *   - UART/PLIC/CLINT 都是 MMIO，NEMU 作为 REF 时由 DUT 侧注入结果，
 *     不依赖本文件；本文件只服务于 NEMU 独立运行。
 *   - mtime 以宿主单调时间为基准，按 CONFIG_RISCV_XV6_MTIME_FREQ
 *     缩放（默认 1 MHz，即宿主微秒），因此定时器中断不是指令级
 *     确定的；需要确定性指令流时应在第一个定时器中断前截断，或由
 *     DUT 注入 trap。DTB timebase 和 OpenSBI NPC_MTIME_FREQ 必须
 *     使用同一个频率。
 ***************************************************************************************/

#include <isa.h>
#include <device/map.h>
#include <memory/host.h>
#include <utils.h>
#include <unistd.h>
#include <sys/select.h>
#include <errno.h>

#ifndef CONFIG_TARGET_AM
#include "../../monitor/sdb/sdb.h"
#endif

#define XV6_CLINT_BASE 0x02000000u
#define XV6_CLINT_SIZE 0x00010000u
#define XV6_PLIC_BASE  0x0c000000u
#define XV6_PLIC_SIZE  0x00400000u
#define XV6_UART_BASE  0x10000000u
#define XV6_UART_SIZE  0x00000008u

#define CLINT_MTIMECMP_OFF 0x4000u
#define CLINT_MTIME_OFF    0xbff8u

#define PLIC_NR_SOURCES 1024
#define PLIC_NR_WORDS   (PLIC_NR_SOURCES / 32)
#define PLIC_UART_IRQ   10
#define PLIC_ENABLE_LEGACY 0x2000u
#define PLIC_ENABLE_S      0x2080u
#define PLIC_THRESHOLD_LEGACY 0x200000u
#define PLIC_THRESHOLD_S      0x201000u
#define PLIC_CLAIM_LEGACY     0x200004u
#define PLIC_CLAIM_S          0x201004u

#define UART_RX_FIFO_SIZE 64

static uint8_t *clint_base = NULL;
static uint8_t *plic_base = NULL;
static uint8_t *uart_base = NULL;

static uint64_t clint_mtimecmp = UINT64_MAX;

static uint32_t plic_priority[PLIC_NR_SOURCES];
static uint32_t plic_pending[PLIC_NR_WORDS];
static uint32_t plic_enable[PLIC_NR_WORDS];
static uint32_t plic_threshold;

static uint8_t uart_rx_fifo[UART_RX_FIFO_SIZE];
static unsigned uart_rx_head, uart_rx_tail, uart_rx_count;
static bool stdin_eof;

static inline unsigned uart_rx_available(void)
{
  return uart_rx_count;
}

static bool uart_irq_level(void)
{
  // IER bit0 = 接收中断使能；FIFO 非空时保持电平有效。
  return (uart_base != NULL) && (uart_base[1] & 0x01) && (uart_rx_count != 0);
}

static void uart_rx_push(uint8_t ch)
{
  if (uart_rx_count == UART_RX_FIFO_SIZE) {
    return; // FIFO 满：丢弃，避免阻塞参考模型
  }
  uart_rx_fifo[uart_rx_tail] = ch;
  uart_rx_tail = (uart_rx_tail + 1) % UART_RX_FIFO_SIZE;
  uart_rx_count++;
}

static uint8_t uart_rx_pop(void)
{
  if (uart_rx_count == 0) {
    return 0;
  }
  const uint8_t ch = uart_rx_fifo[uart_rx_head];
  uart_rx_head = (uart_rx_head + 1) % UART_RX_FIFO_SIZE;
  uart_rx_count--;
  return ch;
}

static void uart_poll_stdin(void)
{
#ifndef CONFIG_TARGET_AM
  // 非 batch 模式下 stdin 属于 SDB；只有 batch 模式才把 stdin 当 UART 输入，
  // 否则自由运行时会吞掉后续的 SDB 命令。
  if (!sdb_batch_mode()) {
    return;
  }
#endif
  if (stdin_eof) {
    return;
  }

  fd_set readfds;
  FD_ZERO(&readfds);
  FD_SET(STDIN_FILENO, &readfds);
  struct timeval tv = { .tv_sec = 0, .tv_usec = 0 };
  const int ready = select(STDIN_FILENO + 1, &readfds, NULL, NULL, &tv);
  if (ready <= 0) {
    return;
  }

  // 一次只读 FIFO 剩余空间，避免覆盖未处理字符。
  const unsigned space = UART_RX_FIFO_SIZE - uart_rx_count;
  if (space == 0) {
    return;
  }
  uint8_t buf[UART_RX_FIFO_SIZE];
  const ssize_t n = read(STDIN_FILENO, buf, space);
  if (n > 0) {
    for (ssize_t i = 0; i < n; i++) {
      uart_rx_push(buf[i]);
    }
  } else if (n == 0) {
    stdin_eof = true;
  }
}

static void uart_io_handler(uint32_t offset, int len, bool is_write)
{
  Assert(offset < XV6_UART_SIZE && len == 1,
         "UART 只支持 1 字节寄存器访问（offset=%u, len=%d）", offset, len);

  if (!is_write) {
    uint8_t value = 0;
    switch (offset) {
      case 0: // RBR
        value = uart_rx_pop();
        break;
      case 1: // IER
        value = uart_base[1];
        break;
      case 2: // IIR：FIFO 非空且接收中断使能时报告 RX data available
        value = (uart_irq_level() ? 0x04 : 0x01);
        break;
      case 3: // LCR
        value = uart_base[3];
        break;
      case 4: // MCR
        value = uart_base[4];
        break;
      case 5: // LSR：THRE | TEMT，RX FIFO 非空时 DataReady
        value = 0x60 | (uart_rx_available() != 0 ? 0x01 : 0x00);
        break;
      case 6: // MSR
        value = 0;
        break;
      case 7: // SCR
        value = uart_base[7];
        break;
      default:
        break;
    }
    uart_base[offset] = value;
    return;
  }

  switch (offset) {
    case 0: // THR：直接输出到宿主 stderr，和 NEMU 原有串口一致
      putc(uart_base[0], stderr);
      fflush(stderr);
      break;
    case 1: // IER
      break;
    case 2: // FCR：bit1/bit2 清 RX FIFO
      if (uart_base[2] & 0x06) {
        uart_rx_head = uart_rx_tail = uart_rx_count = 0;
      }
      break;
    default:
      break;
  }
}

static bool plic_pending_irq(void)
{
  for (int source = 1; source < PLIC_NR_SOURCES; source++) {
    const uint32_t mask = 1u << (source % 32);
    if ((plic_pending[source / 32] & mask) &&
        (plic_enable[source / 32] & mask) &&
        plic_priority[source] > plic_threshold) {
      return true;
    }
  }
  return false;
}

static uint32_t plic_claim(void)
{
  int best = 0;
  uint32_t best_priority = 0;
  for (int source = 1; source < PLIC_NR_SOURCES; source++) {
    const uint32_t mask = 1u << (source % 32);
    if ((plic_pending[source / 32] & mask) &&
        (plic_enable[source / 32] & mask) &&
        plic_priority[source] > plic_threshold &&
        (best == 0 || plic_priority[source] > best_priority)) {
      best = source;
      best_priority = plic_priority[source];
    }
  }
  if (best != 0) {
    plic_pending[best / 32] &= ~(1u << (best % 32));
    // UART 是电平源：claim 后 FIFO 仍有数据时 pending 必须重新拉高。
    if (best == PLIC_UART_IRQ && uart_irq_level()) {
      plic_pending[PLIC_UART_IRQ / 32] |= 1u << (PLIC_UART_IRQ % 32);
    }
  }
  return (uint32_t)best;
}

static void plic_update_irq(void)
{
  if (plic_pending_irq()) {
    cpu.mip |= (1u << 9); // SEIP
  } else {
    cpu.mip &= ~(1u << 9);
  }
}

static void plic_io_handler(uint32_t offset, int len, bool is_write)
{
  Assert(len == 4 && (offset & 3u) == 0,
         "PLIC 只支持 32 位对齐访问（offset=%u, len=%d）", offset, len);

  if (!is_write) {
    uint32_t value = 0;
    if (offset < PLIC_NR_SOURCES * 4) {
      value = plic_priority[offset / 4];
    } else if (offset == 0x1000) {
      value = plic_pending[0]; // 当前只使用 source < 32
    } else if (offset >= PLIC_ENABLE_LEGACY &&
               offset < PLIC_ENABLE_LEGACY + PLIC_NR_WORDS * 4) {
      value = plic_enable[(offset - PLIC_ENABLE_LEGACY) / 4];
    } else if (offset >= PLIC_ENABLE_S &&
               offset < PLIC_ENABLE_S + PLIC_NR_WORDS * 4) {
      value = plic_enable[(offset - PLIC_ENABLE_S) / 4];
    } else if (offset == PLIC_THRESHOLD_LEGACY ||
               offset == PLIC_THRESHOLD_S) {
      value = plic_threshold;
    } else if (offset == PLIC_CLAIM_LEGACY || offset == PLIC_CLAIM_S) {
      value = plic_claim();
    }
    host_write(plic_base + offset, 4, value);
    plic_update_irq();
    return;
  }

  const uint32_t value = (uint32_t)host_read(plic_base + offset, 4);
  if (offset < PLIC_NR_SOURCES * 4) {
    const int source = offset / 4;
    if (source > 0 && source < PLIC_NR_SOURCES) {
      plic_priority[source] = value;
    }
  } else if (offset >= PLIC_ENABLE_LEGACY &&
             offset < PLIC_ENABLE_LEGACY + PLIC_NR_WORDS * 4) {
    plic_enable[(offset - PLIC_ENABLE_LEGACY) / 4] = value;
  } else if (offset >= PLIC_ENABLE_S &&
             offset < PLIC_ENABLE_S + PLIC_NR_WORDS * 4) {
    plic_enable[(offset - PLIC_ENABLE_S) / 4] = value;
  } else if (offset == PLIC_THRESHOLD_LEGACY || offset == PLIC_THRESHOLD_S) {
    plic_threshold = value;
  } else if (offset == PLIC_CLAIM_LEGACY || offset == PLIC_CLAIM_S) {
    // complete：claim 读已经清除 pending，这里无需额外动作。
  }
  plic_update_irq();
}

static void clint_update(void)
{
  if (get_mtime() >= clint_mtimecmp) {
    cpu.mip |= (1u << 7); // MTIP
  } else {
    cpu.mip &= ~(1u << 7);
  }
}

static void clint_io_handler(uint32_t offset, int len, bool is_write)
{
  if (!is_write) {
    if (offset >= CLINT_MTIME_OFF && offset + len <= CLINT_MTIME_OFF + 8) {
      const uint64_t now = get_mtime();
      memcpy(clint_base + offset, (const uint8_t *)&now + (offset - CLINT_MTIME_OFF), len);
    } else if (offset >= CLINT_MTIMECMP_OFF &&
               offset + len <= CLINT_MTIMECMP_OFF + 8) {
      memcpy(clint_base + offset,
             (const uint8_t *)&clint_mtimecmp + (offset - CLINT_MTIMECMP_OFF), len);
    }
    return;
  }

  if (offset >= CLINT_MTIMECMP_OFF && offset + len <= CLINT_MTIMECMP_OFF + 8) {
    clint_mtimecmp = host_read(clint_base + CLINT_MTIMECMP_OFF, 8);
    clint_update();
  }
}

void init_riscv_xv6(void)
{
  uart_base = new_space(XV6_UART_SIZE);
  plic_base = new_space(XV6_PLIC_SIZE);
  clint_base = new_space(XV6_CLINT_SIZE);
  memset(uart_base, 0, XV6_UART_SIZE);
  memset(plic_base, 0, XV6_PLIC_SIZE);
  memset(clint_base, 0, XV6_CLINT_SIZE);

  uart_rx_head = uart_rx_tail = uart_rx_count = 0;
  stdin_eof = false;
  clint_mtimecmp = UINT64_MAX;

  add_mmio_map("xv6-clint", XV6_CLINT_BASE, clint_base, XV6_CLINT_SIZE,
               clint_io_handler);
  add_mmio_map("xv6-plic", XV6_PLIC_BASE, plic_base, XV6_PLIC_SIZE,
               plic_io_handler);
  add_mmio_map("xv6-uart", XV6_UART_BASE, uart_base, XV6_UART_SIZE,
               uart_io_handler);
}

/*
 * 每个 guest 指令后由 device_update() 调用：
 *   - 刷新 CLINT mtime 比较结果（MTIP）
 *   - 低频轮询 stdin，把输入送进 UART RX FIFO
 *   - 维护 PLIC 的 SEIP 电平
 */
void riscv_xv6_update(void)
{
  if (uart_base == NULL) {
    return;
  }

  static uint64_t last_poll = 0;
  static bool last_uart_level = false;
  const uint64_t now = get_mtime();

  clint_update();
  if (now - last_poll >= 1000) { // 1 ms
    last_poll = now;
    uart_poll_stdin();
  }

  /*
   * UART source 10 是电平触发。只有电平变化时才扫描 PLIC，
   * 否则每指令扫描 1024 个 source 会把解释器拖到不可用。
   * claim 路径会在 FIFO 仍非空时自行重新置位 pending。
   */
  const bool uart_level = uart_irq_level();
  if (uart_level != last_uart_level) {
    if (uart_level) {
      plic_pending[PLIC_UART_IRQ / 32] |= 1u << (PLIC_UART_IRQ % 32);
    } else {
      plic_pending[PLIC_UART_IRQ / 32] &= ~(1u << (PLIC_UART_IRQ % 32));
    }
    plic_update_irq();
    last_uart_level = uart_level;
  }
}
