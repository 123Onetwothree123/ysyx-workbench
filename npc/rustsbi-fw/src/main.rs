//! NPC 最小 RustSBI M 模式固件。
//!
//! 启动流程：
//! 1. `_start` 关中断、清 BSS、设置 M 模式栈；
//! 2. `rust_main` 初始化 16550 UART，打印版本和平台信息；
//! 3. 配置 medeleg/mideleg/mie，把 mtvec 指向 M 模式陷入入口；
//! 4. 设置 mstatus.MPP=S、mepc=SBI_NEXT_ADDR，`mret` 进入 S 模式
//!    next-stage（默认 0x80200000）。
//!
//! 本固件只实现 NPC 需要的 SBI 子集：Base、TIME、sPI、RFNC、HSM、SRST、
//! DBCN，以及兼容旧 payload 的 legacy 0x00..0x08。没有 F/D、MMU、多 hart
//! 和设备树解析；SBI_FDT_ENABLE=y 时只把内嵌 DTB 复制到约定地址并
//! 通过 a1 传给 next-stage，不解析 DTB。PMP 只在 CONFIG_HAS_PMP=y 时
//! 配置。这些限制在 npc/README_RUSTSBI.md 中说明。

#![no_std]
#![no_main]

use core::arch::{asm, global_asm};
use core::fmt::Write;
use core::panic::PanicInfo;

mod platform;
mod trap;

use platform::{
    uart_init, uart_put_hex, uart_puts, NpcConsole, NpcEnvInfo, NpcFence, NpcHsm, NpcIpi, NpcReset,
    NpcTimer,
};
use rustsbi::RustSBI;

const FIRMWARE_BASE: usize = parse_hex(env!("NPC_SBI_FW_BASE"));
const NEXT_STAGE: usize = parse_hex(env!("NPC_SBI_NEXT_ADDR"));
const PLATFORM_NAME: &str = env!("NPC_SBI_PLATFORM");
const HAS_PMP: bool = matches!(env!("NPC_SBI_HAS_PMP").as_bytes(), b"y" | b"Y" | b"1");
const FDT_ENABLE: bool = matches!(env!("NPC_SBI_FDT_ENABLE").as_bytes(), b"y" | b"Y" | b"1");
#[cfg_attr(not(npc_fdt), allow(dead_code))]
const FDT_ADDR: usize = parse_hex(env!("NPC_SBI_FDT_ADDR"));

#[cfg(npc_fdt)]
static FDT_BLOB: &[u8] = include_bytes!(env!("NPC_SBI_FDT_DTB_PATH"));

const MEDELEG_VALUE: usize = 0xf5ff; // 除 S/M ecall 外全部委托给 S 模式
const MIDELEG_VALUE: usize = 0x222; // SSIP | STIP | SEIP

/// `env!` 里的十六进制字符串在 const 上下文中转成 usize。
const fn parse_hex(text: &str) -> usize {
    let bytes = text.as_bytes();
    let mut index = 0;
    if bytes.len() >= 2 && bytes[0] == b'0' && (bytes[1] == b'x' || bytes[1] == b'X') {
        index = 2;
    }
    let mut value = 0usize;
    while index < bytes.len() {
        let digit = match bytes[index] {
            b'0'..=b'9' => bytes[index] - b'0',
            b'a'..=b'f' => bytes[index] - b'a' + 10,
            b'A'..=b'F' => bytes[index] - b'A' + 10,
            _ => panic!("NPC_SBI_* 地址不是合法十六进制数"),
        };
        value = value * 16 + digit as usize;
        index += 1;
    }
    value
}

#[derive(RustSBI)]
pub struct NpcSbi {
    pub console: NpcConsole,
    pub timer: NpcTimer,
    pub ipi: NpcIpi,
    pub fence: NpcFence,
    pub hsm: NpcHsm,
    pub reset: NpcReset,
    pub info: NpcEnvInfo,
}

impl NpcSbi {
    pub const fn new() -> Self {
        Self {
            console: NpcConsole,
            timer: NpcTimer,
            ipi: NpcIpi,
            fence: NpcFence,
            hsm: NpcHsm,
            reset: NpcReset,
            info: NpcEnvInfo,
        }
    }
}

global_asm!(
    r#"
    .section .text.entry
    .globl _start
    .type _start, @function
_start:
    .option push
    .option norvc
    .option norelax
    la    gp, __global_pointer$
    .option pop
    la    sp, __stack_top

    csrw  mie, zero
    csrw  mip, zero
    csrw  mideleg, zero
    csrw  medeleg, zero
    csrw  satp, zero

    la    t0, __bss_start
    la    t1, __bss_end
1:
    bgeu  t0, t1, 2f
    sd    zero, 0(t0)
    addi  t0, t0, 8
    j     1b
2:
    call  rust_main
3:
    wfi
    j     3b
    .size _start, .-_start
"#
);

#[no_mangle]
pub extern "C" fn rust_main() -> ! {
    uart_init();
    uart_puts("\r\n");
    uart_puts("[rustsbi-npc] RustSBI ");
    uart_puts(rustsbi::VERSION);
    uart_puts(" (vendor/rustsbi 27a9c7d)\r\n");
    uart_puts("[rustsbi-npc] platform : ");
    uart_puts(PLATFORM_NAME);
    uart_puts("\r\n");
    uart_put_hex("[rustsbi-npc] firmware : ", FIRMWARE_BASE);
    uart_put_hex("[rustsbi-npc] uart     : ", 0x1000_0000);
    uart_put_hex("[rustsbi-npc] clint    : ", 0x0200_0000);
    uart_put_hex("[rustsbi-npc] next     : ", NEXT_STAGE);
    if !FDT_ENABLE {
        uart_puts("[rustsbi-npc] fdt      : none (a1=0)\r\n");
    }

    trap::install();

    unsafe {
        // 定时器中断：MTIP 由 M 模式接收，再由固件注入 STIP。
        asm!("csrw mie, {}", in(reg) 1usize << 7);
        asm!("csrw mip, zero");
        asm!("csrw mideleg, {}", in(reg) MIDELEG_VALUE);
        asm!("csrw medeleg, {}", in(reg) MEDELEG_VALUE);
        // 允许 S 模式读取 cycle/time/instret（NPC 的 time 目前恒为 0）。
        asm!("csrw mcounteren, {}", in(reg) 0x7usize);
        asm!("csrw satp, zero");
        asm!("csrw stvec, zero");
        asm!("csrw sie, zero");
        asm!("csrw sip, zero");
        // M 模式陷入栈：mtvec 入口通过 mscratch 与 S 模式 sp 交换。
        asm!("csrw mscratch, {}", in(reg) stack_top());
        // 清掉可能残留的 CLINT 定时器比较值。
        platform::set_mtimecmp(u64::MAX);
    }

    uart_puts("[rustsbi-npc] entering supervisor mode\r\n");
    enter_supervisor();
}

unsafe fn stack_top() -> usize {
    extern "C" {
        static __stack_top: u8;
    }
    core::ptr::addr_of!(__stack_top) as usize
}

/// 把内嵌 DTB 复制到 SBI_FDT_ADDR，并返回应放进 a1 的值。
///
/// 没有启用 FDT 时返回 0，保持进入 S 模式时 a1=0 的旧行为。
fn fdt_next_arg1() -> usize {
    if !FDT_ENABLE {
        return 0;
    }

    #[cfg(npc_fdt)]
    {
        unsafe {
            let destination = FDT_ADDR as *mut u8;
            // 逐字节 volatile 写：不依赖 MMU，也不依赖编译器生成 memcpy。
            for (offset, byte) in FDT_BLOB.iter().enumerate() {
                core::ptr::write_volatile(destination.add(offset), *byte);
            }
            asm!("fence rw, rw");

            let magic = [
                core::ptr::read_volatile((FDT_ADDR + 0) as *const u8),
                core::ptr::read_volatile((FDT_ADDR + 1) as *const u8),
                core::ptr::read_volatile((FDT_ADDR + 2) as *const u8),
                core::ptr::read_volatile((FDT_ADDR + 3) as *const u8),
            ];
            if u32::from_be_bytes(magic) != 0xd00d_feed {
                uart_put_hex("[rustsbi-npc] invalid DTB at ", FDT_ADDR);
                platform::halt_simulation(1);
            }
            uart_put_hex("[rustsbi-npc] fdt      : ", FDT_ADDR);
            uart_put_hex("[rustsbi-npc] fdt size : ", FDT_BLOB.len());
        }
        FDT_ADDR
    }

    #[cfg(not(npc_fdt))]
    {
        0
    }
}

fn enter_supervisor() -> ! {
    unsafe {
        // 读取当前 mstatus，设置 MPP=S、MPIE=1、SIE=0。
        let mut mstatus: usize;
        asm!("csrr {}, mstatus", out(reg) mstatus);
        mstatus &= !(0b11usize << 11);
        mstatus |= 0b01usize << 11; // MPP = S
        mstatus |= 1usize << 7; // MPIE = 1，mret 后 MIE=1，M 定时器中断可进
        mstatus &= !(1usize << 1); // sstatus.SIE = 0，由 next-stage 自己打开
        asm!("csrw mstatus, {}", in(reg) mstatus);
        asm!("csrw mepc, {}", in(reg) NEXT_STAGE);

        // NPC 默认不实现 PMP；只有 CONFIG_HAS_PMP=y 时才写 PMP CSR。
        // 配置为一条覆盖全部物理地址的 NAPOT 项，否则 S 模式取指会被
        // “没有匹配 PMP 项”拒绝（QEMU virt 就实现了 PMP）。
        if HAS_PMP {
            asm!("csrw pmpaddr0, {}", in(reg) 0x003f_ffff_ffff_ffffusize);
            asm!("csrw pmpcfg0, {}", in(reg) 0x1fusize);
        }

        // 进入 S 模式时 a0=hartid，a1=FDT 地址（未启用 FDT 时为 0）。
        // NPC 目前没有实现 mhartid CSR，读取会触发 illegal instruction；
        // 本固件只支持单 hart，直接传 0。
        let hartid: usize = 0;
        let fdt = fdt_next_arg1();
        asm!(
            "mv a0, {hartid}",
            "mv a1, {fdt}",
            "mret",
            hartid = in(reg) hartid,
            fdt = in(reg) fdt,
            options(noreturn)
        );
    }
}

struct UartWriter;

impl core::fmt::Write for UartWriter {
    fn write_str(&mut self, text: &str) -> core::fmt::Result {
        uart_puts(text);
        Ok(())
    }
}

#[panic_handler]
fn panic(info: &PanicInfo) -> ! {
    uart_puts("\r\n[rustsbi-npc] PANIC: ");
    let _ = write!(UartWriter, "{info}");
    uart_puts("\r\n");
    platform::halt_simulation(1)
}
