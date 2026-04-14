#include <ftrace.h>
#include <readelf.h>
#include <stdlib.h>

#ifdef CONFIG_FTRACE

FtraceState GlobalFtraceState;

static bool FtraceFrameStackResizeExact(FtraceFrameStack *Stack, size_t NewSize)
{
    if (Stack == NULL)
    {
        return false;
    }
    if (NewSize == 0)
    {
        free(Stack->Data);
        Stack->Data = NULL;
        Stack->Size = 0;
        Stack->Capacity = 0;
        return true;
    }
    FtraceFrame *NewData = (FtraceFrame *)realloc(Stack->Data, sizeof(FtraceFrame) * NewSize);
    if (NewData == NULL)
    {
        return false;
    }
    Stack->Data = NewData;
    Stack->Size = NewSize;
    Stack->Capacity = NewSize;
    return true;
}
static bool FtraceFrameStackPushExact(FtraceFrameStack *Stack, const FtraceFrame *Frame)
{
    if (Stack == NULL || Frame == NULL)
    {
        return false;
    }
    size_t NewSize = Stack->Size + 1;
    if (!FtraceFrameStackResizeExact(Stack, NewSize))
    {
        return false;
    }
    Stack->Data[NewSize - 1] = *Frame;
    return true;
}
static bool FtraceFrameStackPopExact(FtraceFrameStack *Stack, FtraceFrame *Out)
{
    if (Stack == NULL || Stack->Size == 0)
    {
        return false;
    }
    FtraceFrame Top = Stack->Data[Stack->Size - 1];
    size_t NewSize = Stack->Size - 1;
    if (!FtraceFrameStackResizeExact(Stack, NewSize))
    {
        return false;
    }
    if (Out != NULL)
    {
        *Out = Top;
    }
    return true;
}
static bool FtraceEventVectorResizeExact(FtraceEventVector *Vector, size_t NewSize)
{
    if (Vector == NULL)
    {
        return false;
    }
    if (NewSize == 0)
    {
        free(Vector->Data);
        Vector->Data = NULL;
        Vector->Size = 0;
        Vector->Capacity = 0;
        return true;
    }
    FtraceEvent *NewData = (FtraceEvent *)realloc(Vector->Data, sizeof(FtraceEvent) * NewSize);
    if (NewData == NULL)
    {
        return false;
    }
    Vector->Data = NewData;
    Vector->Size = NewSize;
    Vector->Capacity = NewSize;
    return true;
}
static bool FtraceEventVectorPushExact(FtraceEventVector *Vector, const FtraceEvent *Event)
{
    if (Vector == NULL || Event == NULL)
    {
        return false;
    }
    size_t NewSize = Vector->Size + 1;
    if (!FtraceEventVectorResizeExact(Vector, NewSize))
    {
        return false;
    }
    Vector->Data[NewSize - 1] = *Event;
    return true;
}
bool FtraceStateInit(FtraceState *State)
{
    if (State == NULL)
    {
        return false;
    }

    memset(State, 0, sizeof(*State));
    State->Enabled = false;
    State->RecordHistory = true;

    State->CallStack.Data = NULL;
    State->CallStack.Size = 0;
    State->CallStack.Capacity = 0;

    State->History.Data = NULL;
    State->History.Size = 0;
    State->History.Capacity = 0;
    return true;
}
void FtraceStateDestroy(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    free(State->CallStack.Data);
    free(State->History.Data);
    memset(State, 0, sizeof(*State));
}
void FtraceStateReset(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    FtraceFrameStackResizeExact(&State->CallStack, 0);
    FtraceEventVectorResizeExact(&State->History, 0);
}
void FtraceEnable(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    State->Enabled = true;
}
void FtraceDisable(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    State->Enabled = false;
}
bool FtraceIsEnabled(const FtraceState *State)
{
    if (State == NULL)
    {
        return false;
    }
    return State->Enabled;
}
void FtraceSetRecordHistory(FtraceState *State, bool RecordHistory)
{
    if (State == NULL)
    {
        return;
    }
    State->RecordHistory = RecordHistory;
}
bool FtraceOnCall(FtraceState *State, vaddr_t CallPc, vaddr_t FunctionAddress)
{
    if (State == NULL)
    {
        return false;
    }
    if (!State->Enabled)
    {
        return true;
    }
    FtraceFrame Frame;
    Frame.CallPC = CallPc;
    Frame.ReturnPC = CallPc + 4;
    Frame.FunctionAddress = FunctionAddress;
    Frame.FunctionName = ReadelfFindFunctionName(FunctionAddress);
    if (!FtraceFrameStackPushExact(&State->CallStack, &Frame))
    {
        return false;
    }
    if (State->RecordHistory)
    {
        FtraceEvent Event;
        Event.Type = FtraceEventCall;
        Event.CurrentPC = CallPc;
        Event.TargetPC = FunctionAddress;
        Event.FunctionName = Frame.FunctionName;
        Event.Depth = State->CallStack.Size;
        if (!FtraceEventVectorPushExact(&State->History, &Event))
        {
            return false;
        }
    }
    return true;
}
bool FtraceOnReturn(FtraceState *State, vaddr_t CurrentPc, vaddr_t TargetPc)
{
    if (State == NULL)
    {
        return false;
    }
    if (!State->Enabled)
    {
        return true;
    }
    FtraceFrame Frame;
    if (!FtraceFrameStackPopExact(&State->CallStack, &Frame))
    {
        return false;
    }
    if (State->RecordHistory)
    {
        FtraceEvent Event;
        Event.Type = FtraceEventRet;
        Event.CurrentPC = CurrentPc;
        Event.TargetPC = TargetPc;
        Event.FunctionName = Frame.FunctionName;
        Event.Depth = State->CallStack.Size;
        if (!FtraceEventVectorPushExact(&State->History, &Event))
        {
            return false;
        }
    }
    return true;
}
size_t FtraceGetDepth(const FtraceState *State)
{
    if (State == NULL)
    {
        return 0;
    }
    return State->CallStack.Size;
}
const FtraceFrame *FtraceGetTopFrame(const FtraceState *State)
{
    if (State == NULL || State->CallStack.Size == 0)
    {
        return NULL;
    }
    return &State->CallStack.Data[State->CallStack.Size - 1];
}
const FtraceEvent *FtraceGetLastEvent(const FtraceState *State)
{
    if (State == NULL || State->History.Size == 0)
    {
        return NULL;
    }
    return &State->History.Data[State->History.Size - 1];
}
void FtracePrintCurrentStack(const FtraceState *State)
{
    if (State == NULL)
    {
        printf("ftrace: state is NULL\n");
        return;
    }
    printf("ftrace: call stack depth = %zu\n", State->CallStack.Size);
    for (size_t i = 0; i < State->CallStack.Size; i++)
    {
        const FtraceFrame *Frame = &State->CallStack.Data[i];
        printf("  #%zu %s @ " FMT_WORD " (call " FMT_WORD ", ret " FMT_WORD ")\n",
               i,
               Frame->FunctionName ? Frame->FunctionName : "???",
               Frame->FunctionAddress,
               Frame->CallPC,
               Frame->ReturnPC);
    }
}
void FtracePrintHistory(const FtraceState *State)
{
    if (State == NULL)
    {
        printf("ftrace: state is NULL\n");
        return;
    }
    printf("ftrace: history size = %zu\n", State->History.Size);
    for (size_t i = 0; i < State->History.Size; i++)
    {
        const FtraceEvent *Event = &State->History.Data[i];
        const char *Type = (Event->Type == FtraceEventCall) ? "call" : "ret ";
        printf("  #%zu %s %s @ " FMT_WORD " -> " FMT_WORD " (depth %zu)\n",
               i,
               Type,
               Event->FunctionName ? Event->FunctionName : "???",
               Event->CurrentPC,
               Event->TargetPC,
               Event->Depth);
    }
}
void FtracePrintStatus(const FtraceState *State)
{
    if (State == NULL)
    {
        printf("ftrace: state is NULL\n");
        return;
    }
    printf("ftrace: enabled=%s, record_history=%s, depth=%zu, history=%zu\n",
           State->Enabled ? "true" : "false",
           State->RecordHistory ? "true" : "false",
           State->CallStack.Size,
           State->History.Size);
}

#endif
