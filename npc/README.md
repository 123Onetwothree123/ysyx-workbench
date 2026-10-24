Chisel Project Template
=======================

Another version of the [Chisel template](https://github.com/ucb-bar/chisel-template) supporting mill.
mill is another Scala/Java build tool without obscure DSL like SBT. It is much faster than SBT.

Contents at a glance:

* `.gitignore` - helps Git ignore junk like generated files, build products, and temporary files.
* `build.mill` - instructs mill to build the Chisel project
* `Makefile` - rules to call mill
* `src/GCD.scala` - GCD source file
* `src/DecoupledGCD.scala` - another GCD source file
* `src/Elaborate.scala` - wrapper file to call chisel command with the GCD module
* `csrc/Test/unit/src` - Scala/Chisel 单元测试
* `csrc/Test/sbi` - OpenSBI/RustSBI 冒烟测试资产

Feel free to rename or delete files under `src/` or use them as a reference/template.

## Getting Started

First, install mill by referring to the documentation [here](https://com-lihaoyi.github.io/mill).

To run all tests in this design (recommended for test-driven development):
```bash
make test
```

To generate Verilog:
```bash
make verilog
```

## 可选 SBI 固件

NPC 支持 OpenSBI / RustSBI 两种可选的 M 模式 SBI 固件，默认关闭
（`CONFIG_SBI_ENABLE=n`），xv6 仍直接以 M 模式启动。RustSBI 的来源、
Kconfig、构建和测试见 [README_RUSTSBI.md](README_RUSTSBI.md)。
构建 RustSBI：

```bash
make rustsbi
```

需要给 Linux 传递设备树时，使用 `rv64_sbi_fdt_defconfig` 或打开
`CONFIG_SBI_FDT_ENABLE=y`：

```bash
make rv64_sbi_fdt_defconfig
make sbi
make sbi-test
```

DTB 源文件在 `npc/dts/npc.dts`，默认加载/传递地址
`SBI_FDT_ADDR=0x80f00000`；标准 Linux 布局使用 `0x83f00000`。
CPU ISA、`mmu-type` 和 timebase 由 Kconfig 自动生成，`make dtb`
可单独生成到 `build/dtb/`。Linux Image/initramfs 打包见
`make rv64_linux_defconfig && make linux-image`（不会下载/编译 Linux）。
