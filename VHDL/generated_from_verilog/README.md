# NPC Logisim files generated from Verilog

Source of truth:

- `npc/vsrc/RV32E32Reg.v`
- instantiated modules under `npc/vsrc`

Generated files:

- `npc_from_verilog_tunnel_clean.circ`
  - Clean native Logisim topology generated from `npc/vsrc/RV32E32Reg.v`.
  - Main circuit: `RV32E32Reg_top`.
  - Detailed Verilog `.port(signal)` mapping is moved into
    `RV32E32Reg_port_map` so the top-level diagram does not become unreadable.
  - Uses Tunnel labels with an `s_` prefix to avoid Logisim label/component
    name collisions.
  - Does not reuse `D:\ysyx\11\11debug.circ`.
  - Leaf module circuits currently expose Verilog module boundaries; native
    internals still need to be expanded before this file is runnable by native
    Logisim simulation.

- `npc_from_verilog_tunnel.circ`
  - Same content as `npc_from_verilog_tunnel_clean.circ`.
  - Kept under the old filename so reopening the previous file does not show
    the unreadable dense layout again.

- `npc_embedded_vhdl.circ`
  - Logisim project containing VHDL entities generated earlier from the NPC
    Verilog behavior.
  - Behavior comes from `RV32E32Reg.vhd` and `RV32E32Reg_Logisim.vhd`.
  - This is for Logisim VHDL simulation, not native gate-level expansion.

The previous `npc_logisim_clean.circ` mistake has been replaced with the
known runnable `11debug.circ` baseline and should not be treated as a Verilog
NPC conversion.
