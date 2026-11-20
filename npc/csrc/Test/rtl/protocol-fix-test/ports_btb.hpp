#pragma once
#include "Vysyx_26030103_BTB.h"

namespace ports::BTB {
inline auto &clock(Vysyx_26030103_BTB &dut) { return dut.clock; }
inline auto &io_LookupPC(Vysyx_26030103_BTB &dut) { return dut.io_LookupPC; }
inline auto &io_UpdatePC(Vysyx_26030103_BTB &dut) { return dut.io_UpdatePC; }
inline auto &io_UpdateTarget(Vysyx_26030103_BTB &dut) { return dut.io_UpdateTarget; }
inline auto &io_UpdateValid(Vysyx_26030103_BTB &dut) { return dut.io_UpdateValid; }
inline auto &io_flush(Vysyx_26030103_BTB &dut) { return dut.io_flush; }
inline auto &io_hit(Vysyx_26030103_BTB &dut) { return dut.io_hit; }
inline auto &io_target(Vysyx_26030103_BTB &dut) { return dut.io_target; }
inline auto &reset(Vysyx_26030103_BTB &dut) { return dut.reset; }
}
