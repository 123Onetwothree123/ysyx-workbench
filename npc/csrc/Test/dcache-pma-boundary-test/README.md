# DCache PMA cacheability regressions

Leaf-level Verilator regressions for all PMA decisions used by DCache:

- a legal load stays single-beat when its 16-byte cache line would cross a PMA
  region boundary;
- a readable region marked `Cacheable=false` never starts a refill, even at a
  historical 0x8 RAM address;
- a PMA-approved cacheable region outside the historical 0x8/0xa windows can
  refill and hit normally.

```bash
make test
```
