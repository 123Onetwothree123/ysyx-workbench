# ICache PMA boundary test

This is a focused RTL regression for instruction fetches at the end of a PMA
region.  Its custom Chisel harness defines an executable, non-cacheable region
`[0x1000, 0x1005)` so both sides of the boundary can be checked directly:

- a four-byte instruction at `0x1000` is legal and must issue one AXI beat;
- a four-byte instruction at `0x1004` crosses `EndExclusive` and must become a
  local instruction access fault without issuing AXI traffic.

Run it with:

```bash
make test
```

`make run` is an alias, and `make clean` removes generated RTL and Verilator
outputs.
