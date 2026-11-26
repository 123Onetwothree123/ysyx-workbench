module;
// 宏不能跨模块导出，改导出类型别名
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#else
#include "VysyxSoCFull.h"
#endif

export module ysyx_26030103_TargetTop;

#ifdef VRISCV32E_NPC
export using TargetTop = Vriscv32e_npc_SimTop;
#else
export using TargetTop = VysyxSoCFull;
#endif
