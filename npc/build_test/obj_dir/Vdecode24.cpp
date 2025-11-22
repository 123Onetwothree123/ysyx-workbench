// Verilated -*- C++ -*-
// DESCRIPTION: Verilator output: Model implementation (design independent parts)

#include "Vdecode24__pch.h"

//============================================================
// Constructors

Vdecode24::Vdecode24(VerilatedContext* _vcontextp__, const char* _vcname__)
    : VerilatedModel{*_vcontextp__}
    , vlSymsp{new Vdecode24__Syms(contextp(), _vcname__, this)}
    , en{vlSymsp->TOP.en}
    , x{vlSymsp->TOP.x}
    , y{vlSymsp->TOP.y}
    , rootp{&(vlSymsp->TOP)}
{
    // Register model with the context
    contextp()->addModel(this);
}

Vdecode24::Vdecode24(const char* _vcname__)
    : Vdecode24(Verilated::threadContextp(), _vcname__)
{
}

//============================================================
// Destructor

Vdecode24::~Vdecode24() {
    delete vlSymsp;
}

//============================================================
// Evaluation function

#ifdef VL_DEBUG
void Vdecode24___024root___eval_debug_assertions(Vdecode24___024root* vlSelf);
#endif  // VL_DEBUG
void Vdecode24___024root___eval_static(Vdecode24___024root* vlSelf);
void Vdecode24___024root___eval_initial(Vdecode24___024root* vlSelf);
void Vdecode24___024root___eval_settle(Vdecode24___024root* vlSelf);
void Vdecode24___024root___eval(Vdecode24___024root* vlSelf);

void Vdecode24::eval_step() {
    VL_DEBUG_IF(VL_DBG_MSGF("+++++TOP Evaluate Vdecode24::eval_step\n"); );
#ifdef VL_DEBUG
    // Debug assertions
    Vdecode24___024root___eval_debug_assertions(&(vlSymsp->TOP));
#endif  // VL_DEBUG
    vlSymsp->__Vm_deleter.deleteAll();
    if (VL_UNLIKELY(!vlSymsp->__Vm_didInit)) {
        vlSymsp->__Vm_didInit = true;
        VL_DEBUG_IF(VL_DBG_MSGF("+ Initial\n"););
        Vdecode24___024root___eval_static(&(vlSymsp->TOP));
        Vdecode24___024root___eval_initial(&(vlSymsp->TOP));
        Vdecode24___024root___eval_settle(&(vlSymsp->TOP));
    }
    VL_DEBUG_IF(VL_DBG_MSGF("+ Eval\n"););
    Vdecode24___024root___eval(&(vlSymsp->TOP));
    // Evaluate cleanup
    Verilated::endOfEval(vlSymsp->__Vm_evalMsgQp);
}

//============================================================
// Events and timing
bool Vdecode24::eventsPending() { return false; }

uint64_t Vdecode24::nextTimeSlot() {
    VL_FATAL_MT(__FILE__, __LINE__, "", "No delays in the design");
    return 0;
}

//============================================================
// Utilities

const char* Vdecode24::name() const {
    return vlSymsp->name();
}

//============================================================
// Invoke final blocks

void Vdecode24___024root___eval_final(Vdecode24___024root* vlSelf);

VL_ATTR_COLD void Vdecode24::final() {
    Vdecode24___024root___eval_final(&(vlSymsp->TOP));
}

//============================================================
// Implementations of abstract methods from VerilatedModel

const char* Vdecode24::hierName() const { return vlSymsp->name(); }
const char* Vdecode24::modelName() const { return "Vdecode24"; }
unsigned Vdecode24::threads() const { return 1; }
void Vdecode24::prepareClone() const { contextp()->prepareClone(); }
void Vdecode24::atClone() const {
    contextp()->threadPoolpOnClone();
}
