//! 生成 NPC RustSBI 固件的链接脚本。
//!
//! 链接地址不能写死在源码或 Cargo 配置里：`SBI_FW_BASE`、`SBI_FW_SIZE`
//! 和 `SBI_NEXT_ADDR` 来自 Kconfig，构建脚本会通过环境变量传入。
//! 这里把模板展开到 `OUT_DIR/link.ld`，再交给 rust-lld。

use std::{env, fs, path::PathBuf};

const DEFAULT_FW_BASE: u64 = 0x8000_0000;
const DEFAULT_FW_SIZE: u64 = 0x0020_0000;
const DEFAULT_NEXT_ADDR: u64 = 0x8020_0000;
const STACK_SIZE: u64 = 64 * 1024;

fn parse_u64(name: &str, default: u64) -> u64 {
    match env::var(name) {
        Ok(value) => {
            let trimmed = value.trim();
            let parsed = if let Some(hex) = trimmed
                .strip_prefix("0x")
                .or_else(|| trimmed.strip_prefix("0X"))
            {
                u64::from_str_radix(hex, 16)
            } else {
                trimmed.parse::<u64>()
            };
            parsed.unwrap_or_else(|_| {
                panic!("环境变量 {name}={value:?} 不是合法的 64 位整数");
            })
        }
        Err(_) => default,
    }
}

fn main() {
    let fw_base = parse_u64("SBI_FW_BASE", DEFAULT_FW_BASE);
    let fw_size = parse_u64("SBI_FW_SIZE", DEFAULT_FW_SIZE);
    let next_addr = parse_u64("SBI_NEXT_ADDR", DEFAULT_NEXT_ADDR);
    let platform = env::var("SBI_PLATFORM").unwrap_or_else(|_| "npc".to_string());
    // NPC 默认 CONFIG_HAS_PMP=n。PMP CSR 在未实现时访问会触发 illegal
    // instruction，因此只能按 Kconfig 编译期决定是否配置 PMP。
    let has_pmp = env::var("SBI_HAS_PMP").unwrap_or_else(|_| "n".to_string());
    let has_pmp = matches!(has_pmp.as_str(), "y" | "Y" | "1" | "true" | "TRUE");

    assert!(fw_base % 4096 == 0, "SBI_FW_BASE 必须 4KiB 对齐");
    assert!(fw_size >= 64 * 1024, "SBI_FW_SIZE 至少需要 64KiB");
    assert!(
        next_addr >= fw_base + fw_size,
        "SBI_NEXT_ADDR {next_addr:#x} 落入 SBI 固件保留区 [{fw_base:#x}, {:#x})",
        fw_base + fw_size
    );
    assert!(
        fw_base + fw_size <= (1u64 << 32),
        "NPC 仿真的物理窗口位于低 4GiB，SBI_FW_BASE + SBI_FW_SIZE 不能超过 4GiB"
    );

    let template = fs::read_to_string("link.ld.in").expect("读取 link.ld.in 失败");
    let script = template
        .replace("@FW_BASE@", &format!("{fw_base:#x}"))
        .replace("@FW_SIZE@", &format!("{fw_size:#x}"))
        .replace("@STACK_SIZE@", &format!("{STACK_SIZE:#x}"));

    let out_dir = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo 未提供 OUT_DIR"));
    let link_script = out_dir.join("link.ld");
    fs::write(&link_script, script).expect("写入生成的链接脚本失败");

    println!("cargo:rustc-link-arg=-T{}", link_script.display());
    println!("cargo:rustc-link-arg=--gc-sections");
    println!("cargo:rerun-if-changed=link.ld.in");
    println!("cargo:rerun-if-env-changed=SBI_FW_BASE");
    println!("cargo:rerun-if-env-changed=SBI_FW_SIZE");
    println!("cargo:rerun-if-env-changed=SBI_NEXT_ADDR");
    println!("cargo:rerun-if-env-changed=SBI_PLATFORM");
    println!("cargo:rerun-if-env-changed=SBI_HAS_PMP");

    // 供源码在编译期读取；构建脚本已经做过范围校验。
    println!("cargo:rustc-env=NPC_SBI_FW_BASE={fw_base:#x}");
    println!("cargo:rustc-env=NPC_SBI_NEXT_ADDR={next_addr:#x}");
    println!("cargo:rustc-env=NPC_SBI_PLATFORM={platform}");
    println!(
        "cargo:rustc-env=NPC_SBI_HAS_PMP={}",
        if has_pmp { "y" } else { "n" }
    );
}
