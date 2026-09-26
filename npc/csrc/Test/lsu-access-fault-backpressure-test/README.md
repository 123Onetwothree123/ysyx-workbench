# LSU access-fault backpressure regression

This standalone Verilator test checks that the production LSU keeps the AXI
error response associated with a failed load until the LSU output handshake.

The normal CPU cannot create this condition: its WBU is permanently ready, so
whole-core elaboration constant-propagates the LSU's `out.ready` input.  This
test therefore elaborates the production `ysyx_26030103_LSU` as the top module
through a small Scala subclass.  The subclass changes no LSU behavior; it only
keeps the Decoupled output ready signal controllable at the test boundary.

The regression contains two cases:

- an immediate-handshake positive control, which must commit a precise load
  access fault with the original AXI `SLVERR` response;
- four cycles of downstream backpressure, after which the same fault, response
  code, PC, flush signals, and suppressed architectural side effects must still
  be present at commit.

Run it with:

```bash
make clean test
```
