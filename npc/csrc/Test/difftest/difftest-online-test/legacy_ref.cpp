#include <cstddef>
#include <cstdint>

// Models an older reference library that predates the fixed-size RV32
// DiffTest ABI handshake.  The simulator must reject this library instead of
// silently running the guest without differential checking.
extern "C" void difftest_memcpy(std::uint32_t, void *, std::size_t, bool) {}

extern "C" void difftest_regcpy(void *, bool) {}
