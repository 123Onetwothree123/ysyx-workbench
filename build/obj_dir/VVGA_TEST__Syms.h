// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Symbol table internal header
//
// Internal details; most calling programs do not need this header,
// unless using verilator public meta comments.

#ifndef VERILATED_VVGA_TEST__SYMS_H_
#define VERILATED_VVGA_TEST__SYMS_H_  // guard

#include "verilated.h"

// INCLUDE MODEL CLASS

#include "VVGA_TEST.h"

// INCLUDE MODULE CLASSES
#include "VVGA_TEST___024root.h"

// SYMS CLASS (contains all model state)
class alignas(VL_CACHE_LINE_BYTES)VVGA_TEST__Syms final : public VerilatedSyms {
  public:
    // INTERNAL STATE
    VVGA_TEST* const __Vm_modelp;
    VlDeleter __Vm_deleter;
    bool __Vm_didInit = false;

    // MODULE INSTANCE STATE
    VVGA_TEST___024root            TOP;

    // CONSTRUCTORS
    VVGA_TEST__Syms(VerilatedContext* contextp, const char* namep, VVGA_TEST* modelp);
    ~VVGA_TEST__Syms();

    // METHODS
    const char* name() { return TOP.name(); }
};

#endif  // guard
