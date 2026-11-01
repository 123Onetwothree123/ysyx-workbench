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

use rustsbi::{Console, EnvInfo, Fence, HartMask, Hsm, Ipi, Physical, Pmu as RustSbiPMU, Reset, SbiRet, Timer};

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

// ---------------------------------------------------------------------------
// PMU：mcycle/minstret + mhpmcounter3..31
// ---------------------------------------------------------------------------

const PMU_COUNTERS: usize = 32;
const PMU_RAW_EVENT: usize = 0x20000;
const PMU_RAW_EVENT_V2: usize = 0x30000;
const PMU_CFG_SKIP_MATCH: usize = 1 << 0;
const PMU_CFG_CLEAR_VALUE: usize = 1 << 1;
const PMU_CFG_AUTO_START: usize = 1 << 2;
const PMU_CFG_UINH: usize = 1 << 5;
const PMU_CFG_SINH: usize = 1 << 6;
const PMU_CFG_MINH: usize = 1 << 7;
const MHPMEVENT_UINH: usize = 1 << 60;
const MHPMEVENT_SINH: usize = 1 << 61;
const MHPMEVENT_MINH: usize = 1 << 62;

macro_rules! write_hpm_csrw {
    ($index:expr, $value:expr; $($idx:literal => $csr:literal),* $(,)?) => {
        match $index {
            $(
                $idx => unsafe {
                    asm!("csrw {csr}, {value}", csr = const $csr, value = in(reg) $value)
                },
            )*
            _ => {}
        }
    };
}

unsafe fn write_hpmcounter(index: usize, value: usize) {
    write_hpm_csrw!(index, value;
        3 => 0xb03, 4 => 0xb04, 5 => 0xb05, 6 => 0xb06,
        7 => 0xb07, 8 => 0xb08, 9 => 0xb09, 10 => 0xb0a,
        11 => 0xb0b, 12 => 0xb0c, 13 => 0xb0d, 14 => 0xb0e,
        15 => 0xb0f, 16 => 0xb10, 17 => 0xb11, 18 => 0xb12,
        19 => 0xb13, 20 => 0xb14, 21 => 0xb15, 22 => 0xb16,
        23 => 0xb17, 24 => 0xb18, 25 => 0xb19, 26 => 0xb1a,
        27 => 0xb1b, 28 => 0xb1c, 29 => 0xb1d, 30 => 0xb1e,
        31 => 0xb1f,
    );
}

unsafe fn write_mhpmevent(index: usize, value: usize) {
    write_hpm_csrw!(index, value;
        3 => 0x323, 4 => 0x324, 5 => 0x325, 6 => 0x326,
        7 => 0x327, 8 => 0x328, 9 => 0x329, 10 => 0x32a,
        11 => 0x32b, 12 => 0x32c, 13 => 0x32d, 14 => 0x32e,
        15 => 0x32f, 16 => 0x330, 17 => 0x331, 18 => 0x332,
        19 => 0x333, 20 => 0x334, 21 => 0x335, 22 => 0x336,
        23 => 0x337, 24 => 0x338, 25 => 0x339, 26 => 0x33a,
        27 => 0x33b, 28 => 0x33c, 29 => 0x33d, 30 => 0x33e,
        31 => 0x33f,
    );
}

unsafe fn write_pmu_counter(index: usize, value: usize) {
    match index {
        0 => asm!("csrw mcycle, {}", in(reg) value),
        2 => asm!("csrw minstret, {}", in(reg) value),
        3..=31 => write_hpmcounter(index, value),
        _ => {}
    }
}

unsafe fn update_mcountinhibit(index: usize, inhibit: bool) {
    let bit = match index {
        0 => 1usize,
        2 => 1usize << 2,
        3..=31 => 1usize << index,
        _ => return,
    };
    let mut value: usize;
    asm!("csrr {}, mcountinhibit", out(reg) value);
    if inhibit {
        value |= bit;
    } else {
        value &= !bit;
    }
    asm!("csrw mcountinhibit, {}", in(reg) value);
}

fn counter_mask_valid(base: usize, mask: usize) -> bool {
    if mask == 0 {
        return false;
    }
    for bit in 0..(usize::BITS as usize) {
        if mask & (1usize << bit) != 0 && base + bit >= PMU_COUNTERS {
            return false;
        }
    }
    true
}

fn hardware_event_selector(event_idx: usize, event_data: u64) -> Option<usize> {
    if event_idx == PMU_RAW_EVENT || event_idx == PMU_RAW_EVENT_V2 {
        return Some(event_data as usize);
    }
    if let Some(select) = crate::pmu::event_select(event_idx as u32) {
        if select != 0 {
            return Some(select as usize);
        }
        return None;
    }
    // 没有 DTB 映射时只接受与硬件事件号天然一致的通用事件。
    match event_idx {
        1..=4 => Some(event_idx),
        _ => None,
    }
}

pub struct NpcPMU;

impl RustSbiPMU for NpcPMU {
    fn num_counters(&self) -> usize {
        PMU_COUNTERS
    }

    fn counter_get_info(&self, counter_idx: usize) -> SbiRet {
        if counter_idx >= PMU_COUNTERS {
            return SbiRet::invalid_param();
        }
        // S 模式通过 cycle/time/instret/hpmcounter3..31 只读别名访问。
        let csr = 0xc00 + counter_idx;
        let info = csr | (63usize << 12);
        SbiRet::success(info)
    }

    fn counter_config_matching(
        &self,
        counter_idx_base: usize,
        counter_idx_mask: usize,
        config_flags: usize,
        event_idx: usize,
        event_data: u64,
    ) -> SbiRet {
        if !counter_mask_valid(counter_idx_base, counter_idx_mask) {
            return SbiRet::invalid_param();
        }
        let Some(selector) = hardware_event_selector(event_idx, event_data) else {
            return SbiRet::not_supported();
        };

        let mut selected = None;
        for bit in 0..(usize::BITS as usize) {
            if counter_idx_mask & (1usize << bit) == 0 {
                continue;
            }
            let index = counter_idx_base + bit;
            if index == 1 {
                continue;
            }
            if index < 3 {
                let fixed_match = (index == 0 && event_idx == 1) ||
                    (index == 2 && event_idx == 2);
                if !fixed_match {
                    continue;
                }
            } else if let Some(mask) = crate::pmu::event_counter_mask(event_idx as u32) {
                if mask & (1u32 << index) == 0 {
                    continue;
                }
            }
            selected = Some(index);
            break;
        }
        let Some(index) = selected else {
            return SbiRet::not_supported();
        };

        if index >= 3 {
            let mut event = selector;
            if config_flags & PMU_CFG_UINH != 0 {
                event |= MHPMEVENT_UINH;
            }
            if config_flags & PMU_CFG_SINH != 0 {
                event |= MHPMEVENT_SINH;
            }
            if config_flags & PMU_CFG_MINH != 0 {
                event |= MHPMEVENT_MINH;
            }
            unsafe { write_mhpmevent(index, event) };
        }
        if config_flags & PMU_CFG_CLEAR_VALUE != 0 {
            unsafe { write_pmu_counter(index, 0) };
        }
        if config_flags & PMU_CFG_AUTO_START != 0 {
            unsafe { update_mcountinhibit(index, false) };
        }

        let _ = PMU_CFG_SKIP_MATCH;
        SbiRet::success(index)
    }

    fn counter_start(
        &self,
        counter_idx_base: usize,
        counter_idx_mask: usize,
        start_flags: usize,
        initial_value: u64,
    ) -> SbiRet {
        if !counter_mask_valid(counter_idx_base, counter_idx_mask) {
            return SbiRet::invalid_param();
        }
        for bit in 0..(usize::BITS as usize) {
            if counter_idx_mask & (1usize << bit) == 0 {
                continue;
            }
            let index = counter_idx_base + bit;
            if index == 1 {
                continue;
            }
            if start_flags & 1 != 0 {
                unsafe { write_pmu_counter(index, initial_value as usize) };
            }
            unsafe { update_mcountinhibit(index, false) };
        }
        SbiRet::success(0)
    }

    fn counter_stop(
        &self,
        counter_idx_base: usize,
        counter_idx_mask: usize,
        stop_flags: usize,
    ) -> SbiRet {
        if !counter_mask_valid(counter_idx_base, counter_idx_mask) {
            return SbiRet::invalid_param();
        }
        for bit in 0..(usize::BITS as usize) {
            if counter_idx_mask & (1usize << bit) == 0 {
                continue;
            }
            let index = counter_idx_base + bit;
            if index == 1 {
                continue;
            }
            unsafe { update_mcountinhibit(index, true) };
            if stop_flags & 1 != 0 && index >= 3 {
                unsafe { write_mhpmevent(index, 0) };
            }
        }
        SbiRet::success(0)
    }

    fn counter_fw_read(&self, _counter_idx: usize) -> SbiRet {
        SbiRet::invalid_param()
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
