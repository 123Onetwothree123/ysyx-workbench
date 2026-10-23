//! NPC 最小平台适配：16550 UART、SiFive CLINT、单 hart IPI。
//!
//! 地址布局与 NPC 的 PMA/RTL 保持一致：
//! - UART16550：0x1000_0000（THR/RBR/LSR 等字节寄存器）
//! - CLINT：    0x0200_0000（mtimecmp=+0x4000，mtime=+0xbff8）
//! - RAM：      0x8000_0000..0x8400_0000（direct-NPC 64MiB 窗口）
//!
//! 本文件刻意只使用 volatile MMIO 和内联汇编，不依赖 Rust 的原子类型，
//! 因此 NPC 关闭 A 扩展时也能运行。

use core::arch::asm;
use core::ptr::{read_volatile, write_volatile};

use rustsbi::{Console, EnvInfo, Fence, HartMask, Hsm, Ipi, Physical, Reset, SbiRet, Timer};

pub const UART_BASE: usize = 0x1000_0000;
pub const CLINT_BASE: usize = 0x0200_0000;
pub const RAM_BASE: usize = 0x8000_0000;
pub const RAM_SIZE: usize = 0x0400_0000;

const UART_THR: usize = UART_BASE;
const UART_RBR: usize = UART_BASE;
const UART_IER: usize = UART_BASE + 1;
const UART_FCR: usize = UART_BASE + 2;
const UART_LCR: usize = UART_BASE + 3;
const UART_LSR: usize = UART_BASE + 5;

const UART_LSR_DATA_READY: u8 = 1 << 0;
const UART_LSR_THR_EMPTY: u8 = 1 << 5;

const CLINT_MTIMECMP: usize = CLINT_BASE + 0x4000;

pub const MIP_SSIP: usize = 1 << 1;
pub const MIP_STIP: usize = 1 << 5;

#[inline]
unsafe fn mmio_read_u8(address: usize) -> u8 {
    read_volatile(address as *const u8)
}

#[inline]
unsafe fn mmio_write_u8(address: usize, value: u8) {
    write_volatile(address as *mut u8, value);
}

#[inline]
unsafe fn mmio_write_u32(address: usize, value: u32) {
    write_volatile(address as *mut u32, value);
}

/// 初始化 16550：8N1、FIFO 打开、关闭 UART 中断。
///
/// NPC 的 UART 模型不要求真实波特率，QEMU 的 16550 也能接受这组设置；
/// 这样同一份固件可以同时在 NPC 和 QEMU virt 上做冒烟测试。
pub fn uart_init() {
    unsafe {
        mmio_write_u8(UART_IER, 0x00);
        mmio_write_u8(UART_LCR, 0x03);
        mmio_write_u8(UART_FCR, 0x07);
    }
}

pub fn uart_put(byte: u8) {
    unsafe {
        // NPC 的 LSR.THRE 恒为 1；QEMU 上则等待发送保持寄存器空。
        while mmio_read_u8(UART_LSR) & UART_LSR_THR_EMPTY == 0 {
            core::hint::spin_loop();
        }
        mmio_write_u8(UART_THR, byte);
    }
}

pub fn uart_puts(text: &str) {
    for byte in text.bytes() {
        if byte == b'\n' {
            uart_put(b'\r');
        }
        uart_put(byte);
    }
}

/// 打印形如 `prefix0x...` 的一行，避免为了启动日志引入格式化分配器。
pub fn uart_put_hex(prefix: &str, value: usize) {
    uart_puts(prefix);
    uart_puts("0x");
    let digits = (usize::BITS / 4) as usize;
    for index in (0..digits).rev() {
        let nibble = ((value >> (index * 4)) & 0xf) as u8;
        let character = if nibble < 10 {
            b'0' + nibble
        } else {
            b'a' + nibble - 10
        };
        uart_put(character);
    }
    uart_puts("\r\n");
}

pub fn uart_try_get() -> Option<u8> {
    unsafe {
        if mmio_read_u8(UART_LSR) & UART_LSR_DATA_READY != 0 {
            Some(mmio_read_u8(UART_RBR))
        } else {
            None
        }
    }
}

/// 写 mtimecmp。先写高 32 位为全 1，再写低 32 位，最后写高 32 位，
/// 避免 64 位值从大变小的时候中途产生一次假的定时器中断。
pub fn set_mtimecmp(value: u64) {
    let high = (value >> 32) as u32;
    let low = value as u32;
    unsafe {
        mmio_write_u32(CLINT_MTIMECMP + 4, u32::MAX);
        mmio_write_u32(CLINT_MTIMECMP, low);
        mmio_write_u32(CLINT_MTIMECMP + 4, high);
    }
}

/// machine timer 中断由 M 模式固件接收；清掉 mtimecmp 后把 STIP 注入
/// S 模式。NPC 的默认 timer_path=clint_mtip 只产生 MTIP，不会自动产生 STIP。
pub fn acknowledge_mtimer() {
    set_mtimecmp(u64::MAX);
    unsafe {
        asm!("csrs mip, {}", in(reg) MIP_STIP);
    }
}

pub fn clear_stip() {
    unsafe {
        asm!("csrc mip, {}", in(reg) MIP_STIP);
    }
}

pub fn set_ssip() {
    unsafe {
        asm!("csrs mip, {}", in(reg) MIP_SSIP);
    }
}

pub fn clear_ssip() {
    unsafe {
        asm!("csrc mip, {}", in(reg) MIP_SSIP);
    }
}

fn ram_contains(address: usize, length: usize) -> bool {
    if address < RAM_BASE {
        return false;
    }
    let Some(end) = address.checked_add(length) else {
        return false;
    };
    end <= RAM_BASE + RAM_SIZE
}

fn selected_hart0(mask: HartMask) -> Option<bool> {
    let (bits, base) = mask.into_inner();
    if base == usize::MAX {
        // hart_mask_base = -1：表示所有可用 hart。
        return Some(true);
    }
    if bits == 0 {
        return Some(false);
    }
    if base == 0 {
        if bits & !1 != 0 {
            None
        } else {
            Some(true)
        }
    } else {
        None
    }
}

/// Base 扩展的机器信息。
///
/// 不使用 rustsbi 的 `machine` feature：NPC 当前只实现 mvendorid/marchid，
/// 没有 mimpid CSR；machine feature 在 get_mimpid 时会执行 csrr 0xf13，
/// 在 NPC 上会变成 illegal instruction。这里返回 RTL 中固定的值，
/// mimpid 用 0 表示“未实现”。
pub struct NpcEnvInfo;

impl EnvInfo for NpcEnvInfo {
    fn mvendorid(&self) -> usize {
        0x7973_7978 // 'ysyx'，与 npc/src/EXU/CSR 中的 Mvendorid_rdata 一致
    }

    fn marchid(&self) -> usize {
        0x018d_3017 // NPC 自定义 marchid
    }

    fn mimpid(&self) -> usize {
        0
    }
}

pub struct NpcConsole;

impl Console for NpcConsole {
    fn write(&self, bytes: Physical<&[u8]>) -> SbiRet {
        let length = bytes.num_bytes();
        let address = bytes.phys_addr_lo();
        if bytes.phys_addr_hi() != 0 || !ram_contains(address, length) {
            return SbiRet::invalid_param();
        }
        let data = unsafe { core::slice::from_raw_parts(address as *const u8, length) };
        for &byte in data {
            uart_put(byte);
        }
        SbiRet::success(length)
    }

    fn read(&self, bytes: Physical<&mut [u8]>) -> SbiRet {
        let length = bytes.num_bytes();
        let address = bytes.phys_addr_lo();
        if bytes.phys_addr_hi() != 0 || !ram_contains(address, length) {
            return SbiRet::invalid_param();
        }
        let data = unsafe { core::slice::from_raw_parts_mut(address as *mut u8, length) };
        let mut count = 0;
        while count < data.len() {
            match uart_try_get() {
                Some(byte) => {
                    data[count] = byte;
                    count += 1;
                }
                None => break,
            }
        }
        SbiRet::success(count)
    }

    fn write_byte(&self, byte: u8) -> SbiRet {
        uart_put(byte);
        SbiRet::success(0)
    }
}

pub struct NpcTimer;

impl Timer for NpcTimer {
    fn set_timer(&self, stime_value: u64) {
        // SBI 规范要求 set_timer 清除已经挂起的定时器中断。
        clear_stip();
        set_mtimecmp(stime_value);
    }
}

pub struct NpcIpi;

impl Ipi for NpcIpi {
    fn send_ipi(&self, hart_mask: HartMask) -> SbiRet {
        match selected_hart0(hart_mask) {
            Some(true) => {
                // 单 hart 平台：直接在自己的 mip.SSIP 上注入 S 模式软中断。
                set_ssip();
                SbiRet::success(0)
            }
            Some(false) => SbiRet::success(0),
            None => SbiRet::invalid_param(),
        }
    }
}

pub struct NpcFence;

impl Fence for NpcFence {
    fn remote_fence_i(&self, hart_mask: HartMask) -> SbiRet {
        if selected_hart0(hart_mask).is_none() {
            return SbiRet::invalid_param();
        }
        unsafe {
            asm!("fence.i");
        }
        SbiRet::success(0)
    }

    fn remote_sfence_vma(&self, hart_mask: HartMask, _start_addr: usize, _size: usize) -> SbiRet {
        if selected_hart0(hart_mask).is_none() {
            return SbiRet::invalid_param();
        }
        // SBI 的 RFNC 是“地址范围”语义，而单条 sfence.vma 只能按页/ASID
        // 刷新。这里保守地做一次全量刷新，语义上覆盖请求范围。
        unsafe {
            asm!("sfence.vma zero, zero");
        }
        SbiRet::success(0)
    }

    fn remote_sfence_vma_asid(
        &self,
        hart_mask: HartMask,
        _start_addr: usize,
        _size: usize,
        asid: usize,
    ) -> SbiRet {
        if selected_hart0(hart_mask).is_none() {
            return SbiRet::invalid_param();
        }
        // 刷新该 ASID 的全部映射，同样覆盖请求的地址范围。
        unsafe {
            asm!("sfence.vma zero, {}", in(reg) asid);
        }
        SbiRet::success(0)
    }
}

pub struct NpcHsm;

impl Hsm for NpcHsm {
    fn hart_start(&self, hartid: usize, _start_addr: usize, _opaque: usize) -> SbiRet {
        if hartid != 0 {
            // 单 hart 平台，没有可启动的第二个 hart。
            SbiRet::invalid_param()
        } else {
            // hart 0 就是当前 hart，已经处于 STARTED 状态。
            SbiRet::already_available()
        }
    }

    fn hart_stop(&self) -> SbiRet {
        // 单 hart 平台停止自己等价于整机停机；NPC 用 custom-0 halt 结束仿真。
        halt_simulation(0)
    }

    fn hart_get_status(&self, hartid: usize) -> SbiRet {
        if hartid == 0 {
            SbiRet::success(sbi_spec::hsm::hart_state::STARTED)
        } else {
            SbiRet::invalid_param()
        }
    }

    fn hart_suspend(&self, _suspend_type: u32, _resume_addr: usize, _opaque: usize) -> SbiRet {
        SbiRet::not_supported()
    }
}

pub struct NpcReset;

impl Reset for NpcReset {
    fn system_reset(&self, reset_type: u32, reset_reason: u32) -> SbiRet {
        match reset_type {
            sbi_spec::srst::RESET_TYPE_SHUTDOWN
            | sbi_spec::srst::RESET_TYPE_COLD_REBOOT
            | sbi_spec::srst::RESET_TYPE_WARM_REBOOT => {
                let _ = reset_reason;
                uart_puts("\r\n[rustsbi-npc] system reset -> halt simulation\r\n");
                halt_simulation(0)
            }
            _ => SbiRet::invalid_param(),
        }
    }
}

/// 用 NPC 的 custom-0 halt 指令结束仿真（a0=0 表示 GOOD）。
///
/// 这不是标准 RISC-V 指令，只用于 NPC 仿真环境；真实硬件上应替换为
/// SoC 的复位/电源管理寄存器。
pub fn halt_simulation(code: usize) -> ! {
    unsafe {
        asm!(
            ".word 0x0000000b",
            in("a0") code,
            options(noreturn)
        )
    }
}
