# RTL issue regression test

This project converts source-review findings into executable regressions.  It
does not patch or replace production RTL.

The production snapshot deliberately uses `MUL_IMPL=wallace` and
`MUL_PIPELINE=2` so the multiplier throughput test exercises a core whose
internal pipeline can accept one request per cycle.

## Tests

- `xbar_local_decerr_interleave_test`: a local four-beat DECERR burst is
  interleaved with a mapped SoC response.  The local burst must still contain
  exactly `ARLEN + 1` beats.
- `exu_mdu_ordering_test`: verifies that an IRQ cannot cross an older busy MEM
  instruction merely because the current EXU input is an M instruction, and
  checks that a configured pipelined multiplier accepts independent
  back-to-back requests.
- `mul_core_pipeline_test`: positive control proving that the underlying
  Wallace compression core itself really can accept and return back-to-back
  requests with `MUL_PIPELINE=2`.
- `lsu_bad_bid_test`: a B response with the wrong ID must not complete or retire
  the current store.
- `stage_connect_flush_test`: directly wraps the production `StageConnect`
  helper and checks the `flushCurrent=false`/downstream-stalled corner.
- `icache_pma_boundary_test`: uses a custom non-word-aligned executable region
  to check that instruction permission covers all four fetched bytes, not only
  the starting address.
- `ConfigGuardTest`: checks that the public Scala configuration rejects cache,
  BTB and RAS geometries which the implementation cannot safely elaborate.

## Usage

```bash
make -j4 all
make functional-test
make test
```

`make test` runs every probe even if one fails, then returns nonzero if any
correctness requirement is violated.  A failure which reproduces a reviewed
defect is intentional until the production RTL is fixed; after a fix the same
test becomes its permanent regression guard.

`make functional-test` is the focused passing gate for the Xbar DECERR,
MDU/IRQ ordering, pipelined MDU, LSU BID and StageConnect fixes.  The broader
target continues to report the independent custom-PMA and configuration-guard
findings until those are addressed separately.

Individual targets are `xbar-test`, `mul-core-test`, `exu-test`, `lsu-test`,
`stage-connect-test`, `icache-pma-test`, and `config-test`.
