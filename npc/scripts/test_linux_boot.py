#!/usr/bin/env python3
"""Linux 启动环境配置矩阵测试。

只使用 dummy Image/initramfs/固件，不下载、编译或运行 Linux。
覆盖：
  - Kconfig -> DTB 的 riscv,isa / riscv,isa-extensions / mmu-type；
  - SYNTH_FREQ -> timebase-frequency；
  - initramfs 地址 + 实际大小 -> linux,initrd-start/end；
  - pack_linux_image.sh 的地址校验、重叠检测、负向失败与 sha256 输出；
  - CUSTOM MMU + Linux 必须明确失败。

用法：
  cd npc && make linux-boot-test
  python3 scripts/test_linux_boot.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Dict, Iterable, Optional, Tuple

NPC_HOME = Path(__file__).resolve().parent.parent
WORKBENCH_HOME = NPC_HOME.parent
BUILD_DTB = NPC_HOME / "scripts" / "build_dtb.sh"
PACK = NPC_HOME / "scripts" / "pack_linux_image.sh"

IMAGE_ADDR = 0x80200000
INITRAMFS_ADDR = 0x82000000
FDT_ADDR = 0x83F00000
RAM_BASE = 0x80000000
RAM_SIZE = 0x04000000
FW_BASE = 0x80000000
FW_SIZE = 0x00200000


def base_config(
    *,
    xlen: int = 64,
    m: bool = True,
    a: bool = True,
    c: bool = False,
    f: bool = False,
    d: bool = False,
    has_s: bool = True,
    has_u: bool = True,
    has_mmu: bool = True,
    geometry: str = "standard",
    levels: Optional[int] = None,
    page_size: int = 4096,
    pte_size: Optional[int] = None,
    table_size: int = 4096,
    pte_format: Optional[str] = None,
    synth_freq: int = 450,
    linux: bool = True,
    initramfs: bool = False,
    initramfs_path: str = "",
    initramfs_addr: int = INITRAMFS_ADDR,
    fdt_addr: int = FDT_ADDR,
    image_path: str = "Image",
) -> Dict[str, object]:
    if levels is None:
        levels = 3 if xlen == 64 else 2
    if pte_size is None:
        pte_size = 8 if xlen == 64 else 4
    if pte_format is None:
        pte_format = "riscv64" if xlen == 64 else "riscv32"
    values: Dict[str, object] = {
        "XLEN": xlen,
        "RISCV_M": m,
        "RISCV_A": a,
        "RISCV_C": c,
        "RISCV_F": f,
        "RISCV_D": d,
        "HAS_S": has_s,
        "HAS_U": has_u,
        "HAS_MMU": has_mmu,
        "MMU_LEVELS": levels,
        "MMU_GEOMETRY_MODE": geometry,
        "MMU_PAGE_SIZE_BYTES": page_size,
        "MMU_PTE_SIZE_BYTES": pte_size,
        "MMU_PAGE_TABLE_SIZE_BYTES": table_size,
        "MMU_PTE_FORMAT": pte_format,
        "SYNTH_FREQ": synth_freq,
        "SBI_FW_BASE": FW_BASE,
        "SBI_FW_SIZE": FW_SIZE,
        "SBI_NEXT_ADDR": IMAGE_ADDR,
        "SBI_FDT_ADDR": fdt_addr,
        "SBI_FDT_ENABLE": True,
        "SBI_FDT_PATH": "dts/npc.dts",
        "SBI_FDT_RESERVE_SIZE": 0x10000,
        "NPC_RAM_BASE": RAM_BASE,
        "NPC_RAM_SIZE": RAM_SIZE,
        "LINUX_BOOT_ENABLE": linux,
        "LINUX_IMAGE_PATH": image_path,
        "LINUX_BOOTARGS": "console=ttyS0,115200 earlycon=sbi",
        "LINUX_INITRAMFS_ENABLE": initramfs,
        "LINUX_INITRAMFS_PATH": initramfs_path,
        "LINUX_INITRAMFS_ADDR": initramfs_addr,
    }
    if f:
        values["FPU_IMPL_HARDFLOAT"] = True
        values["FPU_FLEN"] = 64
    return values


def write_config(path: Path, values: Dict[str, object]) -> None:
    lines = []
    for key, value in values.items():
        if isinstance(value, bool):
            if value:
                lines.append(f"CONFIG_{key}=y")
            else:
                lines.append(f"# CONFIG_{key} is not set")
        elif isinstance(value, int):
            lines.append(f"CONFIG_{key}=0x{value:x}" if value >= 0x100 else f"CONFIG_{key}={value}")
        else:
            lines.append(f'CONFIG_{key}="{value}"')
    # Kconfig 的 hex 符号习惯用 0x；上面只为小整数保留十进制（SYNTH_FREQ 等）。
    text = "\n".join(lines) + "\n"
    path.write_text(text, encoding="utf-8")


def make_image(
    path: Path,
    *,
    file_size: int = 4096,
    header_image_size: Optional[int] = None,
    text_offset: int = 0x200000,
    magic: bytes = b"RISCV\x00\x00\x00",
) -> None:
    data = bytearray(file_size)
    if len(data) < 64:
        data.extend(b"\x00" * (64 - len(data)))
    image_size = header_image_size if header_image_size is not None else file_size
    struct.pack_into(
        "<IIQQQIIQ",
        data,
        0,
        0x00000013,  # code0: nop
        0,
        text_offset,
        image_size,
        0,
        2,
        0,
        0,
    )
    data[0x38:0x40] = magic
    data[0x40:0x44] = b"RSC\x05"
    path.write_bytes(data)


def make_cpio(path: Path, size: int = 148) -> None:
    def archive() -> bytes:
        out = bytearray()
        entries = [("init", b"#!/bin/sh\n", 0o100755)]
        ino = 1
        for name, data, mode in entries:
            nameb = name.encode() + b"\0"
            out += b"070701" + struct.pack(
                "<13I", ino, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0,
                len(nameb), 0,
            )
            out += nameb
            out += b"\0" * ((-len(out)) % 4)
            out += data
            out += b"\0" * ((-len(out)) % 4)
            ino += 1
        nameb = b"TRAILER!!!\0"
        out += b"070701" + struct.pack(
            "<13I", 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, len(nameb), 0,
        )
        out += nameb
        out += b"\0" * ((-len(out)) % 4)
        return bytes(out)

    data = archive()
    if size < len(data):
        raise ValueError(f"请求的 cpio 大小 {size} 小于最小归档 {len(data)}")
    path.write_bytes(data + b"\0" * (size - len(data)))


def common_env(config: Path, dtb_dir: Path, **extra: object) -> Dict[str, str]:
    env = os.environ.copy()
    env.update(
        {
            "NPC_HOME": str(NPC_HOME),
            "WORKBENCH_HOME": str(WORKBENCH_HOME),
            "SBI_CONFIG_FILE": str(config),
            "SBI_FDT_OUT_DIR": str(dtb_dir),
            "SBI_FDT_PATH": "dts/npc.dts",
            "SBI_FDT_ENABLE": "y",
            "SBI_FDT_LINUX": "y",
        }
    )
    for key, value in extra.items():
        env[key] = str(value)
    return env


def run_dtb(
    config: Path,
    dtb_dir: Path,
    *,
    expect_ok: bool = True,
    env_extra: Optional[Dict[str, object]] = None,
) -> subprocess.CompletedProcess:
    dtb_dir.mkdir(parents=True, exist_ok=True)
    env = common_env(config, dtb_dir, **(env_extra or {}))
    result = subprocess.run(
        [str(BUILD_DTB)],
        cwd=NPC_HOME,
        env=env,
        text=True,
        capture_output=True,
    )
    if expect_ok and result.returncode != 0:
        raise AssertionError(
            f"build_dtb 失败（rc={result.returncode}）\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    if not expect_ok and result.returncode == 0:
        raise AssertionError(f"build_dtb 本应失败但成功了\nstdout:\n{result.stdout}")
    return result


def fdtget(dtb: Path, node: str, prop: str, typ: str = "s") -> Optional[str]:
    result = subprocess.run(
        ["fdtget", "-t", typ, str(dtb), node, prop],
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def fdtget_int(dtb: Path, node: str, prop: str) -> Optional[int]:
    value = fdtget(dtb, node, prop, "x")
    if value is None:
        return None
    parts = value.split()
    return int(parts[-1], 16)


def write_fake_fw(
    out_dir: Path,
    *,
    dtb: Path,
    fw_base: int = FW_BASE,
    fw_size: int = FW_SIZE,
    fdt_addr: int = FDT_ADDR,
    timebase: int = 450_000_000,
    impl: str = "opensbi",
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    if impl == "opensbi":
        fw = out_dir / "fw.bin"
        info = out_dir / "fw.info"
    else:
        fw = out_dir / "rustsbi-npc.bin"
        info = out_dir / "rustsbi-npc.info"
    fw.write_bytes(b"\x13" * 4096)
    dtb = dtb.resolve()
    (info).write_text(
        "\n".join(
            [
                f"impl={impl}",
                f"fw_base=0x{fw_base:x}",
                f"fw_size=0x{fw_size:x}",
                "fdt_enable=y",
                f"fdt_path={dtb}",
                f"fdt_addr=0x{fdt_addr:x}",
                f"fdt_bytes={dtb.stat().st_size}",
                f"fdt_sha256={hashlib.sha256(dtb.read_bytes()).hexdigest()}",
                f"timebase_freq={timebase}",
                f"fw_bytes={fw.stat().st_size}",
                f"fw_sha256={hashlib.sha256(fw.read_bytes()).hexdigest()}",
                "",
            ]
        ),
        encoding="utf-8",
    )
    return fw


def pack_env(
    config: Path,
    fw_dir: Path,
    dtb_dir: Path,
    out_dir: Path,
    image: Path,
    *,
    initramfs: Optional[Path] = None,
    initramfs_addr: int = INITRAMFS_ADDR,
    fdt_addr: int = FDT_ADDR,
    next_addr: int = IMAGE_ADDR,
    bootargs: str = "console=ttyS0,115200 earlycon=sbi",
    impl: str = "opensbi",
    **extra: object,
) -> Dict[str, str]:
    env = common_env(config, dtb_dir)
    env.update(
        {
            "SBI_ENABLE": "y",
            "SBI_IMPL": impl,
            "SBI_OUT_DIR": str(fw_dir),
            "SBI_FDT_ADDR": f"0x{fdt_addr:x}",
            "SBI_NEXT_ADDR": f"0x{next_addr:x}",
            "SBI_TIMEBASE_FREQ": "450000000",
            "LINUX_IMAGE_PATH": str(image),
            "LINUX_INITRAMFS_ENABLE": "y" if initramfs else "n",
            "LINUX_INITRAMFS_PATH": str(initramfs) if initramfs else "",
            "LINUX_INITRAMFS_ADDR": f"0x{initramfs_addr:x}",
            "LINUX_BOOTARGS": bootargs,
            "LINUX_OUT_DIR": str(out_dir),
        }
    )
    for key, value in extra.items():
        env[key] = str(value)
    return env


def run_pack(env: Dict[str, str], *, expect_ok: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(
        [str(PACK)],
        cwd=NPC_HOME,
        env=env,
        text=True,
        capture_output=True,
    )
    if expect_ok and result.returncode != 0:
        raise AssertionError(
            f"pack_linux_image 失败（rc={result.returncode}）\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    if not expect_ok and result.returncode == 0:
        raise AssertionError(f"pack_linux_image 本应失败但成功了\nstdout:\n{result.stdout}")
    return result


class LinuxBootTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="npc-linux-boot-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    # ---- DTB / Kconfig 矩阵 ------------------------------------------------
    def test_isa_matrix(self) -> None:
        cases = [
            (
                "rv32ima",
                dict(xlen=32, m=True, a=True, c=False, f=False, d=False,
                     levels=2, pte_size=4, pte_format="riscv32"),
                "rv32ima_zicsr_zifencei",
                "i m a zicsr zifencei",
                "riscv,sv32",
            ),
            (
                "rv64ima",
                dict(xlen=64, m=True, a=True, c=False, f=False, d=False),
                "rv64ima_zicsr_zifencei",
                "i m a zicsr zifencei",
                "riscv,sv39",
            ),
            (
                "rv64imafdc",
                dict(xlen=64, m=True, a=True, c=True, f=True, d=True),
                "rv64imafdc_zicsr_zifencei",
                "i m a f d c zicsr zifencei",
                "riscv,sv39",
            ),
            (
                "rv64imafd-no-c",
                dict(xlen=64, m=True, a=True, c=False, f=True, d=True),
                "rv64imafd_zicsr_zifencei",
                "i m a f d zicsr zifencei",
                "riscv,sv39",
            ),
            (
                "rv64imafc-no-d",
                dict(xlen=64, m=True, a=True, c=True, f=True, d=False),
                "rv64imafc_zicsr_zifencei",
                "i m a f c zicsr zifencei",
                "riscv,sv39",
            ),
            (
                "rv64imac-no-f",
                dict(xlen=64, m=True, a=True, c=True, f=False, d=False),
                "rv64imac_zicsr_zifencei",
                "i m a c zicsr zifencei",
                "riscv,sv39",
            ),
            (
                "rv64-sv48",
                dict(xlen=64, m=True, a=True, c=True, f=True, d=True, levels=4),
                "rv64imafdc_zicsr_zifencei",
                "i m a f d c zicsr zifencei",
                "riscv,sv48",
            ),
        ]
        for name, kwargs, isa, exts, mmu in cases:
            with self.subTest(case=name):
                config = self.tmp / f"{name}.config"
                dtb_dir = self.tmp / f"{name}-dtb"
                write_config(config, base_config(linux=False, **kwargs))
                run_dtb(config, dtb_dir, env_extra={"SBI_FDT_LINUX": "n"})
                dtb = dtb_dir / "npc.dtb"
                self.assertEqual(fdtget(dtb, "/cpus/cpu@0", "riscv,isa"), isa)
                self.assertEqual(fdtget(dtb, "/cpus/cpu@0", "riscv,isa-base"), f"rv{kwargs['xlen']}i")
                self.assertEqual(fdtget(dtb, "/cpus/cpu@0", "riscv,isa-extensions"), exts)
                self.assertEqual(fdtget(dtb, "/cpus/cpu@0", "mmu-type"), mmu)
                self.assertEqual(fdtget_int(dtb, "/cpus", "timebase-frequency"), 450_000_000)
                self.assertEqual(
                    fdtget(dtb, "/chosen", "bootargs"),
                    "console=ttyS0,115200 earlycon=sbi",
                )

    def test_timebase_follows_synth_freq(self) -> None:
        for mhz in (100, 450, 800):
            with self.subTest(mhz=mhz):
                config = self.tmp / f"tb-{mhz}.config"
                dtb_dir = self.tmp / f"tb-{mhz}-dtb"
                write_config(config, base_config(linux=False, synth_freq=mhz))
                run_dtb(config, dtb_dir, env_extra={"SBI_FDT_LINUX": "n"})
                dtb = dtb_dir / "npc.dtb"
                self.assertEqual(
                    fdtget_int(dtb, "/cpus", "timebase-frequency"), mhz * 1_000_000
                )

    def test_initramfs_properties(self) -> None:
        initramfs = self.tmp / "initramfs.cpio"
        make_cpio(initramfs, 4096)
        config = self.tmp / "initrd.config"
        write_config(
            config,
            base_config(
                linux=True,
                initramfs=True,
                initramfs_path=str(initramfs),
                initramfs_addr=0x82100000,
            ),
        )
        dtb_dir = self.tmp / "initrd-dtb"
        run_dtb(config, dtb_dir)
        dtb = dtb_dir / "npc.dtb"
        self.assertEqual(fdtget_int(dtb, "/chosen", "linux,initrd-start"), 0x82100000)
        self.assertEqual(fdtget_int(dtb, "/chosen", "linux,initrd-end"), 0x82101000)

    def test_initramfs_disabled_has_no_properties(self) -> None:
        config = self.tmp / "noinitrd.config"
        write_config(config, base_config(linux=True, initramfs=False))
        dtb_dir = self.tmp / "noinitrd-dtb"
        run_dtb(config, dtb_dir)
        dtb = dtb_dir / "npc.dtb"
        self.assertIsNone(fdtget(dtb, "/chosen", "linux,initrd-start"))
        self.assertIsNone(fdtget(dtb, "/chosen", "linux,initrd-end"))

    def test_dtb_and_initramfs_addresses_follow_config(self) -> None:
        initramfs = self.tmp / "addr.cpio"
        make_cpio(initramfs, 4096)
        config = self.tmp / "addr.config"
        write_config(
            config,
            base_config(
                linux=True,
                initramfs=True,
                initramfs_path=str(initramfs),
                initramfs_addr=0x82300000,
                fdt_addr=0x83E00000,
            ),
        )
        dtb_dir = self.tmp / "addr-dtb"
        run_dtb(config, dtb_dir)
        dtb = dtb_dir / "npc.dtb"
        self.assertEqual(fdtget_int(dtb, "/chosen", "linux,initrd-start"), 0x82300000)
        reserved = fdtget(dtb, "/reserved-memory/fdt@83e00000", "reg", "x")
        self.assertIsNotNone(reserved)
        cells = [int(cell, 16) for cell in reserved.split()]
        self.assertEqual(cells[1], 0x83E00000)
        self.assertEqual(cells[3], 0x10000)
        info = (dtb_dir / "npc.dtb.info").read_text(encoding="utf-8")
        self.assertIn("fdt_addr=0x83e00000", info)

    def test_custom_mmu_linux_rejected(self) -> None:
        config = self.tmp / "custom.config"
        write_config(
            config,
            base_config(
                linux=True,
                geometry="custom",
                page_size=1024,
                table_size=16384,
                levels=3,
            ),
        )
        result = run_dtb(
            config,
            self.tmp / "custom-dtb",
            expect_ok=False,
            env_extra={"SBI_FDT_LINUX": "y"},
        )
        self.assertIn("不是标准 Sv32/Sv39/Sv48", result.stderr)

    def test_custom_mmu_non_linux_omits_mmu_type(self) -> None:
        config = self.tmp / "custom-nolinux.config"
        write_config(
            config,
            base_config(
                linux=False,
                geometry="custom",
                page_size=1024,
                table_size=16384,
                levels=3,
            ),
        )
        dtb_dir = self.tmp / "custom-nolinux-dtb"
        run_dtb(config, dtb_dir, env_extra={"SBI_FDT_LINUX": "n"})
        self.assertIsNone(fdtget(dtb_dir / "npc.dtb", "/cpus/cpu@0", "mmu-type"))

    # ---- 打包正向/负向 ----------------------------------------------------
    def _standard_pack_setup(
        self,
        *,
        image_kwargs: Optional[Dict[str, object]] = None,
        initramfs_size: int = 148,
        initramfs_addr: int = INITRAMFS_ADDR,
        config_extra: Optional[Dict[str, object]] = None,
    ) -> Tuple[Dict[str, str], Path, Path, Path, Path]:
        image = self.tmp / "Image"
        initramfs = self.tmp / "initramfs.cpio"
        make_image(image, **(image_kwargs or {}))
        make_cpio(initramfs, initramfs_size)
        cfg = base_config(
            linux=True,
            initramfs=True,
            initramfs_path=str(initramfs),
            initramfs_addr=initramfs_addr,
        )
        if config_extra:
            cfg.update(config_extra)
        config = self.tmp / "pack.config"
        write_config(config, cfg)
        dtb_dir = self.tmp / "pack-dtb"
        run_dtb(config, dtb_dir)
        fw_dir = self.tmp / "pack-fw"
        write_fake_fw(fw_dir, dtb=dtb_dir / "npc.dtb", fdt_addr=FDT_ADDR)
        out_dir = self.tmp / "pack-out"
        env = pack_env(
            config,
            fw_dir,
            dtb_dir,
            out_dir,
            image,
            initramfs=initramfs,
            initramfs_addr=initramfs_addr,
        )
        return env, image, initramfs, dtb_dir, out_dir

    def test_pack_positive_and_sha256(self) -> None:
        env, image, initramfs, dtb_dir, out_dir = self._standard_pack_setup()
        run_pack(env)
        binary = out_dir / "linux-image.bin"
        self.assertTrue(binary.is_file())
        data = binary.read_bytes()
        self.assertEqual(len(data), 0x83F00000 - RAM_BASE + (dtb_dir / "npc.dtb").stat().st_size)
        image_off = IMAGE_ADDR - RAM_BASE
        initrd_off = INITRAMFS_ADDR - RAM_BASE
        dtb_off = FDT_ADDR - RAM_BASE
        self.assertEqual(data[image_off:image_off + image.stat().st_size], image.read_bytes())
        self.assertEqual(
            data[initrd_off:initrd_off + initramfs.stat().st_size],
            initramfs.read_bytes(),
        )
        self.assertEqual(
            data[dtb_off:dtb_off + (dtb_dir / "npc.dtb").stat().st_size],
            (dtb_dir / "npc.dtb").read_bytes(),
        )
        info = (out_dir / "linux-image.bin.info").read_text(encoding="utf-8")
        for needle in (
            "image_addr=0x80200000",
            "initramfs_addr=0x82000000",
            "dtb_addr=0x83f00000",
            "binary_sha256=",
            "timebase_freq=450000000",
        ):
            self.assertIn(needle, info)
        check = subprocess.run(
            ["sha256sum", "-c", "linux-image.bin.sha256"],
            cwd=out_dir,
            text=True,
            capture_output=True,
        )
        self.assertEqual(check.returncode, 0, check.stderr)
        self.assertIn("OK", check.stdout)

    def test_pack_without_initramfs(self) -> None:
        image = self.tmp / "Image"
        make_image(image)
        config = self.tmp / "pack-noinitrd.config"
        write_config(config, base_config(linux=True, initramfs=False))
        dtb_dir = self.tmp / "pack-noinitrd-dtb"
        run_dtb(config, dtb_dir)
        fw_dir = self.tmp / "pack-noinitrd-fw"
        write_fake_fw(fw_dir, dtb=dtb_dir / "npc.dtb")
        out_dir = self.tmp / "pack-noinitrd-out"
        env = pack_env(config, fw_dir, dtb_dir, out_dir, image, initramfs=None)
        run_pack(env)
        self.assertTrue((out_dir / "linux-image.bin").is_file())
        self.assertIsNone(
            fdtget(dtb_dir / "npc.dtb", "/chosen", "linux,initrd-start")
        )
        info = (out_dir / "linux-image.bin.info").read_text(encoding="utf-8")
        self.assertIn("initramfs_enable=n", info)

    def test_pack_image_bad_magic(self) -> None:
        env, image, *_ = self._standard_pack_setup(
            image_kwargs={"magic": b"NOTRISCV"}
        )
        result = run_pack(env, expect_ok=False)
        self.assertIn("magic", result.stderr)

    def test_pack_image_text_offset_mismatch(self) -> None:
        env, image, *_ = self._standard_pack_setup(
            image_kwargs={"text_offset": 0x400000}
        )
        result = run_pack(env, expect_ok=False)
        self.assertIn("text_offset", result.stderr)

    def test_pack_image_overlaps_initramfs(self) -> None:
        env, *_ = self._standard_pack_setup(
            image_kwargs={"header_image_size": (INITRAMFS_ADDR - IMAGE_ADDR) + 1}
        )
        result = run_pack(env, expect_ok=False)
        self.assertIn("重叠", result.stderr)

    def test_pack_initramfs_overlaps_dtb(self) -> None:
        # 默认 initramfs 到 DTB 之间有 31 MiB；用 31 MiB + 1 页触发。
        initramfs = self.tmp / "overlap.cpio"
        make_cpio(initramfs, (FDT_ADDR - INITRAMFS_ADDR) + 0x1000)
        config = self.tmp / "overlap.config"
        write_config(
            config,
            base_config(
                linux=True,
                initramfs=True,
                initramfs_path=str(initramfs),
            ),
        )
        result = run_dtb(config, self.tmp / "overlap-dtb", expect_ok=False)
        self.assertIn("重叠", result.stderr)

    def test_pack_initramfs_out_of_ram(self) -> None:
        initramfs = self.tmp / "oor.cpio"
        make_cpio(
            initramfs,
            (RAM_BASE + RAM_SIZE - 0x83E00000) + 0x1000,
        )
        config = self.tmp / "oor.config"
        write_config(
            config,
            base_config(
                linux=True,
                initramfs=True,
                initramfs_path=str(initramfs),
                initramfs_addr=0x83E00000,
            ),
        )
        result = run_dtb(config, self.tmp / "oor-dtb", expect_ok=False)
        self.assertIn("超出 NPC RAM", result.stderr)

    def test_pack_initramfs_unaligned(self) -> None:
        initramfs = self.tmp / "unaligned.cpio"
        make_cpio(initramfs)
        config = self.tmp / "unaligned.config"
        write_config(
            config,
            base_config(
                linux=True,
                initramfs=True,
                initramfs_path=str(initramfs),
                initramfs_addr=0x82000004,
            ),
        )
        result = run_dtb(config, self.tmp / "unaligned-dtb", expect_ok=False)
        self.assertIn("4 KiB 对齐", result.stderr)

    def test_pack_missing_image(self) -> None:
        env, image, *_ = self._standard_pack_setup()
        image.unlink()
        result = run_pack(env, expect_ok=False)
        self.assertIn("Linux Image 不存在", result.stderr)

    def test_pack_fdt_address_out_of_ram(self) -> None:
        image = self.tmp / "Image"
        make_image(image)
        config = self.tmp / "bad-fdt.config"
        write_config(config, base_config(linux=True, fdt_addr=0x83FF8000))
        result = run_dtb(
            config,
            self.tmp / "bad-fdt-dtb",
            expect_ok=False,
            env_extra={"SBI_FDT_ADDR": "0x83ff8000"},
        )
        self.assertIn("超出 NPC RAM", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
