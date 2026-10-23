//! M 模式陷入入口与 SBI ecall 分发。
//!
//! 设计要点：
//! - M 模式有自己的 64KiB 栈，入口通过 mscratch 与 S 模式的 sp 交换，
//!   不信任 S 模式传来的栈指针。
//! - machine timer 中断（MTIP）在 M 模式确认后清 mtimecmp，并向 mip.STIP
//!   注入 S 模式定时器中断；NPC 默认 timer_path=clint_mtip 不会自动产生 STIP。
//! - S 模式 ecall（mcause=9）交给 RustSBI 的 `handle_ecall`；同时补上
//!   RustSBI 0.4 不再分发的 legacy 0x00..0x08 扩展，保证老 payload 的
//!   console putchar 也能工作。

use core::arch::{asm, global_asm};

use rustsbi::{RustSBI, SbiRet};
use sbi_spec::{base, legacy};

use crate::platform::{
    self, clear_ssip, halt_simulation, set_mtimecmp, set_ssip, uart_put, uart_put_hex, uart_puts,
};
use crate::NpcSbi;

/// 与 npc_trap_entry 中的保存顺序/偏移严格对应。
#[repr(C)]
pub struct TrapFrame {
    pub ra: usize,
    pub gp: usize,
    pub tp: usize,
    pub t0: usize,
    pub t1: usize,
    pub t2: usize,
    pub s0: usize,
    pub s1: usize,
    pub a0: usize,
    pub a1: usize,
    pub a2: usize,
    pub a3: usize,
    pub a4: usize,
    pub a5: usize,
    pub a6: usize,
    pub a7: usize,
    pub s2: usize,
    pub s3: usize,
    pub s4: usize,
    pub s5: usize,
    pub s6: usize,
    pub s7: usize,
    pub s8: usize,
    pub s9: usize,
    pub s10: usize,
    pub s11: usize,
    pub t3: usize,
    pub t4: usize,
    pub t5: usize,
    pub t6: usize,
    pub reserved0: usize,
    pub reserved1: usize,
}

const MCAUSE_INTERRUPT: usize = 1 << (usize::BITS - 1);
const MCAUSE_MACHINE_TIMER: usize = 7;
const MCAUSE_MACHINE_EXTERNAL: usize = 11;
const MCAUSE_SUPERVISOR_ECALL: usize = 9;
const MCAUSE_MACHINE_SOFT: usize = 3;

#[inline]
fn read_mcause() -> usize {
    let value;
    unsafe {
        asm!("csrr {}, mcause", out(reg) value);
    }
    value
}

#[inline]
fn read_mepc() -> usize {
    let value;
    unsafe {
        asm!("csrr {}, mepc", out(reg) value);
    }
    value
}

#[inline]
fn write_mepc(value: usize) {
    unsafe {
        asm!("csrw mepc, {}", in(reg) value);
    }
}

#[inline]
fn read_mtval() -> usize {
    let value;
    unsafe {
        asm!("csrr {}, mtval", out(reg) value);
    }
    value
}

global_asm!(
    r#"
    .section .text
    .align 4
    .global npc_trap_entry
    .type npc_trap_entry, @function
npc_trap_entry:
    # sp = M 模式栈顶，mscratch = S 模式 sp。
    csrrw sp, mscratch, sp
    addi  sp, sp, -256

    sd    ra,   0(sp)
    sd    gp,   8(sp)
    sd    tp,  16(sp)
    sd    t0,  24(sp)
    sd    t1,  32(sp)
    sd    t2,  40(sp)
    sd    s0,  48(sp)
    sd    s1,  56(sp)
    sd    a0,  64(sp)
    sd    a1,  72(sp)
    sd    a2,  80(sp)
    sd    a3,  88(sp)
    sd    a4,  96(sp)
    sd    a5, 104(sp)
    sd    a6, 112(sp)
    sd    a7, 120(sp)
    sd    s2, 128(sp)
    sd    s3, 136(sp)
    sd    s4, 144(sp)
    sd    s5, 152(sp)
    sd    s6, 160(sp)
    sd    s7, 168(sp)
    sd    s8, 176(sp)
    sd    s9, 184(sp)
    sd   s10, 192(sp)
    sd   s11, 200(sp)
    sd    t3, 208(sp)
    sd    t4, 216(sp)
    sd    t5, 224(sp)
    sd    t6, 232(sp)

    mv    a0, sp
    call  npc_trap_handler

    ld    ra,   0(sp)
    ld    gp,   8(sp)
    ld    tp,  16(sp)
    ld    t0,  24(sp)
    ld    t1,  32(sp)
    ld    t2,  40(sp)
    ld    s0,  48(sp)
    ld    s1,  56(sp)
    ld    a0,  64(sp)
    ld    a1,  72(sp)
    ld    a2,  80(sp)
    ld    a3,  88(sp)
    ld    a4,  96(sp)
    ld    a5, 104(sp)
    ld    a6, 112(sp)
    ld    a7, 120(sp)
    ld    s2, 128(sp)
    ld    s3, 136(sp)
    ld    s4, 144(sp)
    ld    s5, 152(sp)
    ld    s6, 160(sp)
    ld    s7, 168(sp)
    ld    s8, 176(sp)
    ld    s9, 184(sp)
    ld   s10, 192(sp)
    ld   s11, 200(sp)
    ld    t3, 208(sp)
    ld    t4, 216(sp)
    ld    t5, 224(sp)
    ld    t6, 232(sp)

    addi  sp, sp, 256
    # 恢复 S 模式 sp，同时把 M 栈顶放回 mscratch。
    csrrw sp, mscratch, sp
    mret
    .size npc_trap_entry, .-npc_trap_entry
"#
);

extern "C" {
    fn npc_trap_entry();
}

pub fn install() {
    unsafe {
        asm!("csrw mtvec, {}", in(reg) npc_trap_entry as usize);
    }
}

#[no_mangle]
pub extern "C" fn npc_trap_handler(frame: *mut TrapFrame) {
    // SAFETY: npc_trap_entry 总是传入指向 256 字节栈帧的指针。
    let frame = unsafe { &mut *frame };
    let cause = read_mcause();

    if cause == (MCAUSE_INTERRUPT | MCAUSE_MACHINE_TIMER) {
        // NPC 的 CLINT 只产生 MTIP；清 mtimecmp 并注入 STIP。
        platform::acknowledge_mtimer();
        return;
    }

    if cause == (MCAUSE_INTERRUPT | MCAUSE_MACHINE_SOFT) {
        // 正常路径下 IPI 直接写 mip.SSIP，不会进入这里；保留清中断逻辑。
        clear_ssip();
        return;
    }

    if cause == (MCAUSE_INTERRUPT | MCAUSE_MACHINE_EXTERNAL) {
        // 本平台没有启用 M 模式外部中断。直接返回会让中断线保持高电平，
        // 因此按不可恢复错误处理，避免静默死循环。
        uart_puts("\r\n[rustsbi-npc] unexpected machine external interrupt\r\n");
        halt_simulation(1);
    }

    if cause == MCAUSE_SUPERVISOR_ECALL {
        handle_ecall(frame);
        write_mepc(read_mepc().wrapping_add(4));
        return;
    }

    uart_puts("\r\n[rustsbi-npc] unexpected machine trap\r\n");
    uart_put_hex("  mcause = ", cause);
    uart_put_hex("  mepc   = ", read_mepc());
    uart_put_hex("  mtval  = ", read_mtval());
    halt_simulation(1);
}

fn handle_ecall(frame: &mut TrapFrame) {
    let eid = frame.a7;
    let fid = frame.a6;
    let params = [frame.a0, frame.a1, frame.a2, frame.a3, frame.a4, frame.a5];

    let ret = if eid <= legacy::LEGACY_SHUTDOWN {
        handle_legacy(eid, params)
    } else if eid == base::EID_BASE
        && fid == base::PROBE_EXTENSION
        && params[0] <= legacy::LEGACY_SHUTDOWN
    {
        // RustSBI 0.4 的 Base probe 不认识 legacy 扩展；这里显式报告可用。
        SbiRet::success(1)
    } else {
        // 每次调用创建一个零大小/无状态的分发器；平台状态都在 MMIO/CSR 中。
        NpcSbi::new().handle_ecall(eid, fid, params)
    };

    // 标准 SBI 与 legacy SBI 的返回寄存器约定相同：a0=error，a1=value。
    // legacy getchar 的特殊处理在 handle_legacy 中把字符放在 error 字段。
    frame.a0 = ret.error;
    frame.a1 = ret.value;
}

fn handle_legacy(eid: usize, params: [usize; 6]) -> SbiRet {
    match eid {
        legacy::LEGACY_SET_TIMER => {
            set_mtimecmp(params[0] as u64);
            platform::clear_stip();
            SbiRet::success(0)
        }
        legacy::LEGACY_CONSOLE_PUTCHAR => {
            uart_put(params[0] as u8);
            SbiRet::success(0)
        }
        legacy::LEGACY_CONSOLE_GETCHAR => loop {
            if let Some(byte) = platform::uart_try_get() {
                // legacy getchar 把字符直接放在 a0；借用 error 字段承载。
                return SbiRet {
                    error: byte as usize,
                    value: 0,
                };
            }
            core::hint::spin_loop();
        },
        legacy::LEGACY_CLEAR_IPI => {
            clear_ssip();
            SbiRet::success(0)
        }
        legacy::LEGACY_SEND_IPI => {
            set_ssip();
            SbiRet::success(0)
        }
        legacy::LEGACY_REMOTE_FENCE_I => {
            unsafe {
                asm!("fence.i");
            }
            SbiRet::success(0)
        }
        legacy::LEGACY_REMOTE_SFENCE_VMA | legacy::LEGACY_REMOTE_SFENCE_VMA_ASID => {
            unsafe {
                asm!("sfence.vma zero, zero");
            }
            SbiRet::success(0)
        }
        legacy::LEGACY_SHUTDOWN => {
            uart_puts("\r\n[rustsbi-npc] legacy shutdown\r\n");
            halt_simulation(0)
        }
        _ => SbiRet::not_supported(),
    }
}
