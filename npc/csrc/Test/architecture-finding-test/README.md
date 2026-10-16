# Architecture finding checks

These tests exercise the current RTL composition and direct-NPC wrapper without
changing production RTL.

- `trap-flush-test` composes the production `StageConnect` and LSU as the
  EXU-to-LSU boundary does. It expects a faulting older load to flush the
  buffered younger ALU instruction. This passes: the three-argument
  `StageConnect` overload sets `flushCurrent=flush`, so the earlier suspected
  trap-flush leak is not present in this connection.
- `npc-reset-test` compiles the repository's actual `SIM_TOP=npc` SystemVerilog
  wrapper and checks that the CPU reset PC matches AXIRAM's `0x80000000` image
  base, then verifies that the first RAM instruction retires. Elaborate renames
  the direct-NPC generated file to `ysyx_26030103_npc.sv` while retaining the
  internal module name `ysyx_26030103`; the NPC build selects that file and
  excludes the SoC file of the same name.

Run both checks with:

```bash
make test
```

The combined target returns nonzero if either architectural check fails.
