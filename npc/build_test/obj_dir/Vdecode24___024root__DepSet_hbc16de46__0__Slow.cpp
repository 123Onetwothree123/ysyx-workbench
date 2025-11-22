// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Design implementation internals
// See Vdecode24.h for the primary calling header

#include "Vdecode24__pch.h"
#include "Vdecode24___024root.h"

VL_ATTR_COLD void Vdecode24___024root___eval_static(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___eval_static\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

VL_ATTR_COLD void Vdecode24___024root___eval_initial(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___eval_initial\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

VL_ATTR_COLD void Vdecode24___024root___eval_final(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___eval_final\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vdecode24___024root___dump_triggers__stl(Vdecode24___024root* vlSelf);
#endif  // VL_DEBUG
VL_ATTR_COLD bool Vdecode24___024root___eval_phase__stl(Vdecode24___024root* vlSelf);

VL_ATTR_COLD void Vdecode24___024root___eval_settle(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___eval_settle\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Init
    IData/*31:0*/ __VstlIterCount;
    CData/*0:0*/ __VstlContinue;
    // Body
    __VstlIterCount = 0U;
    vlSelfRef.__VstlFirstIteration = 1U;
    __VstlContinue = 1U;
    while (__VstlContinue) {
        if (VL_UNLIKELY(((0x64U < __VstlIterCount)))) {
#ifdef VL_DEBUG
            Vdecode24___024root___dump_triggers__stl(vlSelf);
#endif
            VL_FATAL_MT("vsrc/decode24.v", 1, "", "Settle region did not converge.");
        }
        __VstlIterCount = ((IData)(1U) + __VstlIterCount);
        __VstlContinue = 0U;
        if (Vdecode24___024root___eval_phase__stl(vlSelf)) {
            __VstlContinue = 1U;
        }
        vlSelfRef.__VstlFirstIteration = 0U;
    }
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vdecode24___024root___dump_triggers__stl(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___dump_triggers__stl\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VstlTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
    if ((1ULL & vlSelfRef.__VstlTriggered.word(0U))) {
        VL_DBG_MSGF("         'stl' region trigger index 0 is active: Internal 'stl' trigger - first iteration\n");
    }
}
#endif  // VL_DEBUG

void Vdecode24___024root___ico_sequent__TOP__0(Vdecode24___024root* vlSelf);

VL_ATTR_COLD void Vdecode24___024root___eval_stl(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___eval_stl\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1ULL & vlSelfRef.__VstlTriggered.word(0U))) {
        Vdecode24___024root___ico_sequent__TOP__0(vlSelf);
    }
}

VL_ATTR_COLD void Vdecode24___024root___eval_triggers__stl(Vdecode24___024root* vlSelf);

VL_ATTR_COLD bool Vdecode24___024root___eval_phase__stl(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___eval_phase__stl\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Init
    CData/*0:0*/ __VstlExecute;
    // Body
    Vdecode24___024root___eval_triggers__stl(vlSelf);
    __VstlExecute = vlSelfRef.__VstlTriggered.any();
    if (__VstlExecute) {
        Vdecode24___024root___eval_stl(vlSelf);
    }
    return (__VstlExecute);
}

#ifdef VL_DEBUG
VL_ATTR_COLD void Vdecode24___024root___dump_triggers__ico(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___dump_triggers__ico\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VicoTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
    if ((1ULL & vlSelfRef.__VicoTriggered.word(0U))) {
        VL_DBG_MSGF("         'ico' region trigger index 0 is active: Internal 'ico' trigger - first iteration\n");
    }
}
#endif  // VL_DEBUG

#ifdef VL_DEBUG
VL_ATTR_COLD void Vdecode24___024root___dump_triggers__act(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___dump_triggers__act\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VactTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
}
#endif  // VL_DEBUG

#ifdef VL_DEBUG
VL_ATTR_COLD void Vdecode24___024root___dump_triggers__nba(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___dump_triggers__nba\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    if ((1U & (~ vlSelfRef.__VnbaTriggered.any()))) {
        VL_DBG_MSGF("         No triggers active\n");
    }
}
#endif  // VL_DEBUG

VL_ATTR_COLD void Vdecode24___024root___ctor_var_reset(Vdecode24___024root* vlSelf) {
    VL_DEBUG_IF(VL_DBG_MSGF("+    Vdecode24___024root___ctor_var_reset\n"); );
    Vdecode24__Syms* const __restrict vlSymsp VL_ATTR_UNUSED = vlSelf->vlSymsp;
    auto& vlSelfRef = std::ref(*vlSelf).get();
    // Body
    const uint64_t __VscopeHash = VL_MURMUR64_HASH(vlSelf->name());
    vlSelf->x = VL_SCOPED_RAND_RESET_I(2, __VscopeHash, 9409450202036847209ull);
    vlSelf->en = VL_SCOPED_RAND_RESET_I(1, __VscopeHash, 7710216835639188562ull);
    vlSelf->y = VL_SCOPED_RAND_RESET_I(4, __VscopeHash, 11123243248953317070ull);
}
