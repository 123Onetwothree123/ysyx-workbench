// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design internal header
// See VVGA_TEST.h for the primary calling header

#ifndef VERILATED_VVGA_TEST___024ROOT_H_
#define VERILATED_VVGA_TEST___024ROOT_H_  // guard

#include "verilated.h"


class VVGA_TEST__Syms;

class alignas(VL_CACHE_LINE_BYTES) VVGA_TEST___024root final : public VerilatedModule {
  public:

    // DESIGN SPECIFIC STATE
    VL_IN8(clk,0,0);
    VL_IN8(rst_n,0,0);
    VL_OUT8(VGA_R,7,0);
    VL_OUT8(VGA_G,7,0);
    VL_OUT8(VGA_B,7,0);
    VL_OUT8(VGA_HSYNC,0,0);
    VL_OUT8(VGA_VSYNC,0,0);
    VL_OUT8(VGA_BLANK_N,0,0);
    VL_OUT8(LED,0,0);
    CData/*0:0*/ __VstlFirstIteration;
    CData/*0:0*/ __Vtrigprevexpr___TOP__clk__0;
    CData/*0:0*/ __VactContinue;
    SData/*9:0*/ VGA_TEST__DOT__h_addr;
    SData/*9:0*/ VGA_TEST__DOT__v_addr;
    SData/*9:0*/ VGA_TEST__DOT__my_vga_ctrl__DOT__x_cnt;
    SData/*9:0*/ VGA_TEST__DOT__my_vga_ctrl__DOT__y_cnt;
    IData/*23:0*/ VGA_TEST__DOT__rom_dout;
    IData/*31:0*/ __VactIterCount;
    VlUnpacked<IData/*23:0*/, 65536> VGA_TEST__DOT__my_rom__DOT__mem;
    VlTriggerVec<1> __VstlTriggered;
    VlTriggerVec<1> __VactTriggered;
    VlTriggerVec<1> __VnbaTriggered;

    // INTERNAL VARIABLES
    VVGA_TEST__Syms* const vlSymsp;

    // CONSTRUCTORS
    VVGA_TEST___024root(VVGA_TEST__Syms* symsp, const char* v__name);
    ~VVGA_TEST___024root();
    VL_UNCOPYABLE(VVGA_TEST___024root);

    // INTERNAL METHODS
    void __Vconfigure(bool first);
};


#endif  // guard
