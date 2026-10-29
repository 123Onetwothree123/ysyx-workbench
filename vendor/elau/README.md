# ELAU vendor snapshot

- Upstream: https://github.com/pulp-platform/ELAU
- Commit: `b0d113aff6a2d800076f5ebb84f09fba93625bc7`
- License: Solderpad Hardware License v0.51 (see `LICENSE`)

`elau_addc.sv` is a self-contained copy of upstream:

- `src/arith_utils.sv` (`lau_pkg`)
- `src/PrefixAndOr.sv`
- `src/AddC.sv`

with a small NPC adapter module `ysyx_26030103_ElauAdder` appended. The adapter
maps an integer speed selector to `lau_pkg::SLOW`, `MEDIUM`, or `FAST`.

The file is kept in one compilation unit so the SystemVerilog package is
defined before `PrefixAndOr`/`AddC`, independent of file-list ordering.
