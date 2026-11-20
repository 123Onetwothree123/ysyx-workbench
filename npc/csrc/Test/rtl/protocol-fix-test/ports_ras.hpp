#pragma once
#include "Vysyx_26030103_RAS.h"

namespace ports::RAS {
inline auto &clock(Vysyx_26030103_RAS &dut) { return dut.clock; }
inline auto &io_PopValid(Vysyx_26030103_RAS &dut) { return dut.io_PopValid; }
inline auto &io_PushAddress(Vysyx_26030103_RAS &dut) { return dut.io_PushAddress; }
inline auto &io_PushValid(Vysyx_26030103_RAS &dut) { return dut.io_PushValid; }
inline auto &io_flush(Vysyx_26030103_RAS &dut) { return dut.io_flush; }
inline auto &io_nonempty(Vysyx_26030103_RAS &dut) { return dut.io_nonempty; }
inline auto &io_top(Vysyx_26030103_RAS &dut) { return dut.io_top; }
inline auto &reset(Vysyx_26030103_RAS &dut) { return dut.reset; }
}
