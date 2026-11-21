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
#define PTE_N ((word_t)1 << 63)
#define PTE_PBMT_MASK ((word_t)3 << 61)
#define PTE_RESERVED_MASK ((word_t)0x7fu << 54)

static word_t page_fault_cause(int type) {
  if (type == MEM_TYPE_IFETCH) return 12;
  if (type == MEM_TYPE_READ) return 13;
  return 15;
}

static word_t access_fault_cause(int type) {
  if (type == MEM_TYPE_IFETCH) return 1;
  if (type == MEM_TYPE_READ) return 5;
  return 7;
}

static void raise_page_fault(vaddr_t vaddr, int type) {
  isa_raise_mem_exception(page_fault_cause(type), vaddr);
}

static void raise_access_fault(vaddr_t VirtualAddress, int AccessType) {
  isa_raise_mem_exception(access_fault_cause(AccessType), VirtualAddress);
}

static word_t pte_read(paddr_t PteAddress, int PteSize, vaddr_t VirtualAddress, int AccessType) {
  if (!isa_pmp_check(PteAddress, PteSize, MEM_TYPE_READ) ||
      !paddr_is_backed(PteAddress, PteSize)) {
    raise_access_fault(VirtualAddress, AccessType);
  }
  return paddr_read(PteAddress, PteSize);
}

static word_t effective_priv(int AccessType) {
  word_t EffectivePrivilege = cpu.privilege;
  if (EffectivePrivilege == RISCV_PRIV_M && (cpu.mstatus & MSTATUS_MPRV) &&
      AccessType != MEM_TYPE_IFETCH) {
    EffectivePrivilege = (cpu.mstatus & MSTATUS_MPP_MASK) >> MSTATUS_MPP_SHIFT;
  }
  return EffectivePrivilege;
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

static void check_leaf_permissions(word_t Pte, vaddr_t VirtualAddress, int AccessType) {
  const word_t EffectivePrivilege = effective_priv(AccessType);

  if (EffectivePrivilege == RISCV_PRIV_U && (Pte & PTE_U) == 0) {
    raise_page_fault(VirtualAddress, AccessType);
  }
  if (EffectivePrivilege == RISCV_PRIV_S && (Pte & PTE_U)) {
    if (AccessType == MEM_TYPE_IFETCH || (cpu.mstatus & MSTATUS_SUM) == 0) {
      raise_page_fault(VirtualAddress, AccessType);
    }
  }

  if (AccessType == MEM_TYPE_IFETCH && (Pte & PTE_X) == 0) {
    raise_page_fault(VirtualAddress, AccessType);
  } else if (AccessType == MEM_TYPE_READ && (Pte & PTE_R) == 0 &&
             !((cpu.mstatus & MSTATUS_MXR) && (Pte & PTE_X))) {
    raise_page_fault(VirtualAddress, AccessType);
  } else if (AccessType == MEM_TYPE_WRITE && (Pte & PTE_W) == 0) {
    raise_page_fault(VirtualAddress, AccessType);
  }

  if ((Pte & PTE_A) == 0) {
    raise_page_fault(VirtualAddress, AccessType);
  }
  if (AccessType == MEM_TYPE_WRITE && (Pte & PTE_D) == 0) {
    raise_page_fault(VirtualAddress, AccessType);
  }
}

#ifdef CONFIG_RV64
static paddr_t isa_mmu_translate_rv64(vaddr_t vaddr, int type, word_t mode) {
  const int levels = (mode == 8) ? 3 : 4;
  const int va_bits = 12 + 9 * levels;

  const sword_t canonical = (sword_t)vaddr >> (va_bits - 1);
  if (canonical != 0 && canonical != -1) {
    raise_page_fault(vaddr, type);
  }

  paddr_t table = (paddr_t)(BITS(cpu.satp, 43, 0) << 12);
  word_t pte = 0;
  int level;

  for (level = levels - 1; level >= 0; level--) {
    const word_t vpn = BITS(vaddr, 12 + 9 * level + 8, 12 + 9 * level);
    pte = pte_read(table + vpn * 8, 8, vaddr, type);

    if ((pte & PTE_V) == 0) {
      raise_page_fault(vaddr, type);
    }
    if ((pte & (PTE_R | PTE_W | PTE_X)) == 0) {
      if ((pte & (PTE_D | PTE_A | PTE_U | PTE_N | PTE_PBMT_MASK)) != 0 ||
          (pte & PTE_RESERVED_MASK) != 0 || level == 0) {
        raise_page_fault(vaddr, type);
      }
      table = (paddr_t)(BITS(pte, 53, 10) << 12);
      continue;
    }
    break;
  }

  if ((pte & PTE_RESERVED_MASK) != 0) {
    raise_page_fault(vaddr, type);
  }
  const bool pbmte = (cpu.Menvcfg & MENVCFG_PBMTE) != 0;
  if ((pte & PTE_PBMT_MASK) != 0 && !pbmte) {
    raise_page_fault(vaddr, type);
  }
  if ((pte & PTE_W) && (pte & PTE_R) == 0) {
    raise_page_fault(vaddr, type);
  }

  word_t ppn = BITS(pte, 53, 10);
  word_t offset_mask;
  if (pte & PTE_N) {
    if (level != 0 || (ppn & 0xfu) != 0x8u) {
      raise_page_fault(vaddr, type);
    }
    ppn &= ~(word_t)0xfu;
    offset_mask = 0xffffu;
  } else {
    if (level > 0 && BITS(ppn, 9 * level - 1, 0) != 0) {
      raise_page_fault(vaddr, type);
    }
    offset_mask = (word_t)(((uint64_t)1 << (12 + 9 * level)) - 1);
  }

  check_leaf_permissions(pte, vaddr, type);
  return (paddr_t)((ppn << 12) | (vaddr & offset_mask));
}
#else
static paddr_t isa_mmu_translate_sv32(vaddr_t vaddr, int type) {
  paddr_t table = (paddr_t)BITS(cpu.satp, 21, 0) << 12;
  word_t pte = 0;
  int level;

  for (level = 1; level >= 0; level--) {
    const word_t vpn = (level == 1) ? BITS(vaddr, 31, 22) : BITS(vaddr, 21, 12);
    pte = pte_read(table + vpn * 4, 4, vaddr, type);

    if ((pte & PTE_V) == 0) {
      raise_page_fault(vaddr, type);
    }
    if ((pte & (PTE_R | PTE_W | PTE_X)) == 0) {
      if ((pte & (PTE_D | PTE_A | PTE_U)) != 0 || level == 0) {
        raise_page_fault(vaddr, type);
      }
      table = (paddr_t)BITS(pte, 31, 10) << 12;
      continue;
    }
    break;
  }

  if ((pte & PTE_W) && (pte & PTE_R) == 0) {
    raise_page_fault(vaddr, type);
  }

  const word_t ppn = BITS(pte, 31, 10);
  if (level == 1 && (ppn & 0x3ffu) != 0) {
    raise_page_fault(vaddr, type);
  }

  check_leaf_permissions(pte, vaddr, type);
  const word_t offset_mask = (word_t)(((uint64_t)1 << (12 + 9 * level)) - 1);
  return ((paddr_t)ppn << 12) | (vaddr & offset_mask);
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
    const uint64_t raw = (uint64_t)cpu.pmpaddr[i] & (uint64_t)PMP_ADDR_MASK;
    if (a == 0x08u) { // TOR
      const uint64_t previous = (i == 0) ? 0 :
          (((uint64_t)cpu.pmpaddr[i - 1] & (uint64_t)PMP_ADDR_MASK) &
           ~(uint64_t)PMP_TOR_LOW_MASK) << 2;
      const uint64_t upper = (raw & ~(uint64_t)PMP_TOR_LOW_MASK) << 2;
      if (upper <= previous) {
        continue;
      }
      base = previous;
      size = upper - previous;
    } else if (a == 0x10u) { // NA4
      base = raw << 2;
      size = 4;
    } else { // NAPOT
      const uint64_t pmpaddr = raw | (uint64_t)PMP_NAPOT_READ_ONES;
      int trailing_ones = 0;
      const int addr_bits = PMP_ADDR_BITS;
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
