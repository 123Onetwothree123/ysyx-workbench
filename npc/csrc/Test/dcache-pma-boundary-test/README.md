# DCache PMA boundary test

Leaf-level Verilator regression for a legal load whose 16-byte cache line
crosses the end of a 12-byte readable/cacheable PMA region.

The DCache must issue the legal word as one uncached AXI beat rather than
widening it into an illegal four-beat refill.

```bash
make test
```
