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
#include <memory/vaddr.h>
#include <memory/paddr.h>

#define PTE_V 0x001u
#define PTE_R 0x002u
#define PTE_W 0x004u
#define PTE_X 0x008u
#define PTE_U 0x010u
#define PTE_A 0x040u
#define PTE_D 0x080u

static word_t page_fault_cause(int type) {
  if (type == MEM_TYPE_IFETCH) return 12;
  if (type == MEM_TYPE_READ) return 13;
  return 15;
}

static void raise_page_fault(vaddr_t vaddr, int type) {
  isa_raise_mem_exception(page_fault_cause(type), vaddr);
}

int isa_mmu_check(vaddr_t vaddr, int len, int type) {
  (void)vaddr;
  (void)len;

#ifdef CONFIG_RV64
  const word_t mode = BITS(cpu.satp, 63, 60);
  // 只实现 Bare/Sv39/Sv48；遇到未实现的 Sv57 等模式必须明确报错，
  // 不能悄悄按 Bare 继续执行。
  if (mode != 0 && mode != 8 && mode != 9) {
    panic("不支持的 RV64 satp.MODE=%u（仅支持 Bare/Sv39/Sv48）", (unsigned)mode);
  }
  if (mode == 0) {
    return MMU_DIRECT;
  }
#else
  // Sv32：satp.MODE 位于 bit31，1 表示分页开启。
  if (BITS(cpu.satp, 31, 31) == 0) {
    return MMU_DIRECT;
  }
#endif

  // S/U 模式按 satp 分页；M 模式默认直接访问物理地址，
  // 只有 MPRV=1 的数据访问才按 MPP 指定的特权级转换。
  if (cpu.privilege <= RISCV_PRIV_S) {
    return MMU_TRANSLATE;
  }
  if (cpu.privilege == RISCV_PRIV_M &&
      (cpu.mstatus & MSTATUS_MPRV) && type != MEM_TYPE_IFETCH) {
    word_t mpp = (cpu.mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
    return (mpp <= RISCV_PRIV_S) ? MMU_TRANSLATE : MMU_DIRECT;
  }
  return MMU_DIRECT;
}

#ifdef CONFIG_RV64
/*
 * RV64 页表遍历：Sv39 三级、Sv48 四级，PTE 固定 8 字节。
 *
 * 权限、A/D 位与页错误的检查顺序遵循 Privileged Spec：
 * - 非叶表项必须 V=1 且 R/W/X 全 0；非叶表项带 W 是非法页表。
 * - W=1 且 R=0 是非法页表。
 * - 超级页要求低层 PPN 字段为 0。
 * - NEMU 作为参考模型采用 A/D=fault 语义（与 NPC 默认 adMode=fault 一致）：
 *   A=0 或写操作 D=0 时产生页错误，不擅自替软件补写 PTE。
 */
static paddr_t isa_mmu_translate_rv64(vaddr_t vaddr, int type, word_t mode) {
  const int levels = (mode == 8) ? 3 : 4; // Sv39 / Sv48
  const int va_bits = 12 + 9 * levels;

  // 检查虚拟地址的规范形式：高位必须等于有效最高位的符号扩展。
  const sword_t canonical = (sword_t)vaddr >> (va_bits - 1);
  if (canonical != 0 && canonical != -1) {
    raise_page_fault(vaddr, type);
  }

  paddr_t table = (paddr_t)(BITS(cpu.satp, 43, 0) << 12);
  word_t pte = 0;
  int level;

  for (level = levels - 1; level >= 0; level--) {
    const word_t vpn = BITS(vaddr, 12 + 9 * level + 8, 12 + 9 * level);
    const paddr_t pte_addr = table + vpn * 8;
    pte = paddr_read(pte_addr, 8);

    if ((pte & PTE_V) == 0) {
      raise_page_fault(vaddr, type);
    }
    // bit[63:54] 为保留位；Svnapot/Svpbmt 等扩展未实现，非零即非法。
    if (BITS(pte, 63, 54) != 0) {
      raise_page_fault(vaddr, type);
    }
    if ((pte & (PTE_R | PTE_W | PTE_X)) != 0) {
      break; // 叶表项
    }
    // 非叶表项不允许 W=1，且 walk 到最底层仍无叶表项即为页错误。
    if ((pte & PTE_W) || level == 0) {
      raise_page_fault(vaddr, type);
    }
    table = (paddr_t)(BITS(pte, 53, 10) << 12);
  }

  if ((pte & PTE_W) && (pte & PTE_R) == 0) {
    raise_page_fault(vaddr, type);
  }

  const word_t ppn = BITS(pte, 53, 10);
  if (level > 0 && BITS(ppn, 9 * level - 1, 0) != 0) {
    // 超级页的 PPN[level-1:0] 必须为 0，否则 PTE 编码非法。
    raise_page_fault(vaddr, type);
  }

  // MPRV 只影响 M 模式下的 load/store，不影响取指。
  word_t priv = cpu.privilege;
  if (priv == RISCV_PRIV_M && (cpu.mstatus & MSTATUS_MPRV) &&
      type != MEM_TYPE_IFETCH) {
    priv = (cpu.mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
  }

  if (priv == RISCV_PRIV_U && (pte & PTE_U) == 0) {
    raise_page_fault(vaddr, type);
  }
  if (priv == RISCV_PRIV_S && (pte & PTE_U)) {
    // S 模式默认不能访问 U 页；数据访问需要 SUM=1，取指始终禁止。
    if (type == MEM_TYPE_IFETCH || (cpu.mstatus & MSTATUS_SUM) == 0) {
      raise_page_fault(vaddr, type);
    }
  }

  if (type == MEM_TYPE_IFETCH && (pte & PTE_X) == 0) {
    raise_page_fault(vaddr, type);
  } else if (type == MEM_TYPE_READ &&
             (pte & PTE_R) == 0 &&
             !((cpu.mstatus & MSTATUS_MXR) && (pte & PTE_X))) {
    raise_page_fault(vaddr, type);
  } else if (type == MEM_TYPE_WRITE && (pte & PTE_W) == 0) {
    raise_page_fault(vaddr, type);
  }

  if ((pte & PTE_A) == 0) {
    raise_page_fault(vaddr, type);
  }
  if (type == MEM_TYPE_WRITE && (pte & PTE_D) == 0) {
    raise_page_fault(vaddr, type);
  }

  // 叶表项 level 对应的低 (12 + 9*level) 位来自虚拟地址，
  // 因此 2MiB/1GiB 超级页也能得到正确的物理地址。
  const word_t offset_mask = BITMASK(12 + 9 * level);
  return (paddr_t)((ppn << 12) | (vaddr & offset_mask));
}
#else
static paddr_t isa_mmu_translate_sv32(vaddr_t vaddr, int type) {
  // Sv32：虚拟地址划分为 vpn[1] | vpn[0] | offset，satp.PPN 指向第一级页表
  uint32_t vpn1 = BITS(vaddr, 31, 22);
  uint32_t vpn0 = BITS(vaddr, 21, 12);
  uint32_t offset = BITS(vaddr, 11, 0);

  paddr_t pgdir = (paddr_t)BITS(cpu.satp, 21, 0) << 12;
  word_t pte1 = paddr_read(pgdir + vpn1 * 4, 4);
  if ((pte1 & PTE_V) == 0 || (pte1 & (PTE_R | PTE_W | PTE_X)) != 0) {
    raise_page_fault(vaddr, type);
  }
  paddr_t pgtbl = (paddr_t)BITS(pte1, 31, 10) << 12;
  word_t pte0 = paddr_read(pgtbl + vpn0 * 4, 4);
  if ((pte0 & PTE_V) == 0 || (pte0 & (PTE_R | PTE_X)) == 0) {
    raise_page_fault(vaddr, type);
  }

  // MPRV 只影响 M 模式下的 load/store，不影响取指。
  word_t priv = cpu.privilege;
  if (priv == RISCV_PRIV_M && (cpu.mstatus & MSTATUS_MPRV) &&
      type != MEM_TYPE_IFETCH) {
    priv = (cpu.mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
  }

  if (priv == RISCV_PRIV_U && (pte0 & PTE_U) == 0) {
    raise_page_fault(vaddr, type);
  }
  if (priv == RISCV_PRIV_S && (pte0 & PTE_U)) {
    // S 模式默认不能访问 U 页；数据访问需要 SUM=1，取指则始终禁止。
    if (type == MEM_TYPE_IFETCH || (cpu.mstatus & MSTATUS_SUM) == 0) {
      raise_page_fault(vaddr, type);
    }
  }
  if (type == MEM_TYPE_IFETCH && (pte0 & PTE_X) == 0) {
    raise_page_fault(vaddr, type);
  } else if (type == MEM_TYPE_READ &&
             (pte0 & PTE_R) == 0 &&
             !((cpu.mstatus & MSTATUS_MXR) && (pte0 & PTE_X))) {
    raise_page_fault(vaddr, type);
  } else if (type == MEM_TYPE_WRITE && (pte0 & PTE_W) == 0) {
    raise_page_fault(vaddr, type);
  }

  // 保留原有 Sv32 参考模型行为：自动置 A/D，避免改变既有 RV32 软件栈。
  word_t new_pte = pte0 | PTE_A;
  if (type == MEM_TYPE_WRITE) {
    new_pte |= PTE_D;
  }
  if (new_pte != pte0) {
    paddr_write(pgtbl + vpn0 * 4, 4, new_pte);
  }

  // 不再假设内核虚拟地址等于物理地址：Sv32 软件也可以建立任意映射。
  return (paddr_t)(BITS(pte0, 31, 10) << 12) | offset;
}
#endif

paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type) {
  (void)len;
#ifdef CONFIG_RV64
  return isa_mmu_translate_rv64(vaddr, type, BITS(cpu.satp, 63, 60));
#else
  return isa_mmu_translate_sv32(vaddr, type);
#endif
}

bool isa_pmp_check(paddr_t paddr, int len, int type) {
  if (len <= 0) {
    return true;
  }

#ifdef CONFIG_RV64
  /*
   * RV64 NEMU 同时作为无 PMP 配置（例如 NPC 的 HAS_PMP=n）的参考模型使用。
   * 所有表项都关闭时视为 PMP 未实现，直接放行；一旦软件写入了有效表项，
   * 再按标准匹配规则检查。这样既保留 PMP 语义，又不会在复位后把 S/U
   * 模式的第一次取指误判成 access fault。RV32 继续使用原有语义。
   */
  bool pmp_programmed = false;
  for (int i = 0; i < 16; i++) {
    const word_t cfg = (cpu.pmpcfg[PMP_CFG_REG_INDEX(i)] >>
                        PMP_CFG_ENTRY_SHIFT(i)) & 0xffu;
    if ((cfg & 0x18u) != 0) {
      pmp_programmed = true;
      break;
    }
  }
  if (!pmp_programmed) {
    return true;
  }
#endif

  bool m_mode = false;
  if (cpu.privilege == RISCV_PRIV_M) {
    if (!(cpu.mstatus & MSTATUS_MPRV) || type == MEM_TYPE_IFETCH) {
      m_mode = true;
    } else {
      const word_t mpp = (cpu.mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
      if (mpp == RISCV_PRIV_M) {
        m_mode = true;
      }
    }
  }

  const uint64_t start = (uint64_t)paddr;
  const uint64_t end = start + (uint64_t)len;
  for (int i = 0; i < 16; i++) {
    const word_t cfg = (cpu.pmpcfg[PMP_CFG_REG_INDEX(i)] >>
                        PMP_CFG_ENTRY_SHIFT(i)) & 0xffu;
    const word_t a = cfg & 0x18u;
    if (a == 0) {
      continue;
    }
    // M 模式只受锁定表项约束；S/U 模式受所有表项约束。
    if (m_mode && !(cfg & 0x80u)) {
      continue;
    }

    uint64_t base = 0, size = 0;
    const uint64_t pmpaddr = (uint64_t)cpu.pmpaddr[i];
    if (a == 0x08u) { // TOR
      const uint64_t lower = (i == 0) ? 0 : ((uint64_t)cpu.pmpaddr[i - 1] << 2);
      const uint64_t upper = pmpaddr << 2;
      if (upper <= lower) {
        continue;
      }
      base = lower;
      size = upper - lower;
    } else if (a == 0x10u) { // NA4
      base = pmpaddr << 2;
      size = 4;
    } else { // NAPOT
      int trailing_ones = 0;
      const int addr_bits = MUXDEF(CONFIG_RV64, 54, 32);
      while (trailing_ones < addr_bits &&
             (pmpaddr & (1ull << trailing_ones))) {
        trailing_ones++;
      }
      if (trailing_ones >= addr_bits) {
        base = 0;
        size = 1ull << (addr_bits + 3);
      } else {
        const uint64_t unit = 1ull << trailing_ones;
        base = (pmpaddr & ~(unit - 1)) << 2;
        size = 1ull << (trailing_ones + 3);
      }
    }

    if (start >= base && end <= base + size) {
      if (type == MEM_TYPE_IFETCH) return (cfg & 0x04u) != 0;
      if (type == MEM_TYPE_READ) return (cfg & 0x01u) != 0;
      if (type == MEM_TYPE_WRITE) return (cfg & 0x02u) != 0;
    }
  }

  // 没有任何表项匹配：S/U 默认拒绝，M 模式在没有锁定表项匹配时放行。
  return m_mode;
}
