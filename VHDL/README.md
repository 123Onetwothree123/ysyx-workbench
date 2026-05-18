# RV32E32Reg VHDL export

This folder is a hand-converted VHDL version of `npc/vsrc` with the DPI-C,
SDB, gtest, itrace, mtrace, and ftrace-only pieces removed.

Import/analyze order:

1. `rv32e_pkg.vhd`
2. `RV32E32Reg.vhd`
3. `RV32E32Reg_Logisim.vhd`

For Logisim's VHDL component importer, prefer the package-free files in
`logisim_import/`. Import only:

1. `logisim_import/RV32E32Reg_Standalone.vhd`
2. `logisim_import/RV32E32Reg_Logisim_Standalone.vhd`

Do not import `rv32e_pkg.vhd` as a Logisim component; it is a package and has no
entity declaration.

Optional simulation-only file:

- `tb_RV32E32Reg_Logisim.vhd`: tiny testbench for the built-in image.

Top choices:

- `RV32E32Reg`: CPU core with external instruction and data memory ports.
- `RV32E32Reg_Logisim`: small self-contained wrapper for Logisim/GHDL-style
  simulation. It contains 64 KiB RAM mapped at `0x80000000`, initialized with
  the original NPC built-in image:
  `auipc; sb; lbu; ebreak; deadbeef`.

Kept CPU behavior:

- RV32 register/immediate/load/store/branch/jal/jalr/lui/auipc datapath.
- Byte, halfword, and word load/store masking and sign extension.
- CSR support for `mstatus`, `mtvec`, `mepc`, `mcause`, `mcycle/mcycleh`,
  `mvendorid`, and `marchid`.
- `ecall` jumps to `mtvec`, and `mret` jumps to `mepc`.
- `ebreak` is exposed as `ebreak`, with `ebreak_pc` and `ebreak_code`.
- Serial writes to `0x10000000` are exposed by `serial_valid/serial_data` in
  the Logisim wrapper.

Removed simulation-only behavior:

- DPI-C calls: `pmem_read`, `pmem_write`, `npc_ebreak`.
- SDB debug PC/GPR write ports.
- ftrace, itrace, mtrace, difftest, and gtest code.

Memory notes:

- The core still uses byte addresses. For an external Logisim ROM/RAM, connect
  instruction reads at `instr_addr` and data reads/writes at `mem_addr`.
- Reads should return the aligned 32-bit little-endian word at
  `addr & 0xFFFF_FFFC`, matching the original DPI memory model.
- For stores, use `mem_wmask(0)` for byte 0, through `mem_wmask(3)` for byte 3.

The wrapper is intentionally small. For larger AM programs, extend
`RAM_BYTES_C` and replace the RAM initializer with your program image.
