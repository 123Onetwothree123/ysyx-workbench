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

  if (BITS(cpu.satp, 31, 31) == 0) {
    return MMU_DIRECT;
  }

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

paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type) {
  // return MEM_RET_FAIL;
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

  // A/D 位：AM 填表时可以直接置 1；这里再兜底更新一次。
  word_t new_pte = pte0 | PTE_A;
  if (type == MEM_TYPE_WRITE) {
    new_pte |= PTE_D;
  }
  if (new_pte != pte0) {
    paddr_write(pgtbl + vpn0 * 4, 4, new_pte);
  }

  paddr_t pa = (paddr_t)(BITS(pte0, 31, 10) << 12) | offset;
  // 内核地址是恒等映射；用户虚拟地址（如 0x40000000）映射到任意物理页，
  // 不能再断言 pa == vaddr。
  if (vaddr >= 0x80000000u) {
    assert(pa == vaddr);
  }
  return pa;
}

bool isa_pmp_check(paddr_t paddr, int len, int type) {
  if (len <= 0) {
    return true;
  }

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

  const uint64_t start = (uint32_t)paddr;
  const uint64_t end = start + (uint32_t)len;
  for (int i = 0; i < 16; i++) {
    const word_t cfg = (cpu.pmpcfg[i / 4] >> ((i % 4) * 8)) & 0xffu;
    const word_t a = cfg & 0x18u;
    if (a == 0) {
      continue;
    }
    // M 模式只受锁定表项约束；S/U 模式受所有表项约束。
    if (m_mode && !(cfg & 0x80u)) {
      continue;
    }

    uint64_t base = 0, size = 0;
    if (a == 0x08u) { // TOR
      const uint64_t lower = (i == 0) ? 0 : ((uint64_t)cpu.pmpaddr[i - 1] << 2);
      const uint64_t upper = (uint64_t)cpu.pmpaddr[i] << 2;
      if (upper <= lower) {
        continue;
      }
      base = lower;
      size = upper - lower;
    } else if (a == 0x10u) { // NA4
      base = (uint64_t)cpu.pmpaddr[i] << 2;
      size = 4;
    } else { // NAPOT
      const uint32_t addr = cpu.pmpaddr[i];
      int trailing_ones = 0;
      while (trailing_ones < 32 && (addr & (1u << trailing_ones))) {
        trailing_ones++;
      }
      if (trailing_ones == 32) {
        base = 0;
        size = 1ull << 35;
      } else {
        const uint64_t unit = 1ull << trailing_ones;
        base = ((uint64_t)(addr & ~(uint32_t)(unit - 1))) << 2;
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
