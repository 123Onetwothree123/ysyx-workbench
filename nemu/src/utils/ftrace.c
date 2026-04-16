// doxygen注释是ai写的
#include <ftrace.h>
#include <readelf.h>
#include <stdlib.h>

#ifdef CONFIG_FTRACE

FtraceState GlobalFtraceState;

static void FtracePrintEventLine(const FtraceEvent *Event);

/**
 * @brief 精确调整调用栈容量，使其与实际使用大小完全匹配。
 *
 * @details
 * 怕浪费空间，特意设计了一个可以给调用栈精确调整大小的函数（这行注释是我自己写的）
 * 调用本函数后，Stack->Capacity 将严格等于 NewSize，不会预留任何额外空间。
 * 当 NewSize 为 0 时，会彻底释放 Stack->Data 并将所有字段归零。
 *
 * @param[in,out] Stack   指向待调整的 FtraceFrameStack 实例。
 * @param[in]     NewSize 目标元素个数，同时也是新的总容量。
 *
 * @return 调整成功返回 true；若 Stack 为 NULL 或 realloc 失败则返回 false。
 */
static bool FtraceFrameStackResizeExact(FtraceFrameStack *Stack, size_t NewSize)
{
    if (Stack == NULL)
    {
        return false;
    }
    if (NewSize == 0) // 先标记一下，因为栈这里的元素一个都没有，如果这里还保留非空的内存就会浪费掉，直接就释放掉
    {
        free(Stack->Data);
        Stack->Data = NULL;
        Stack->Size = 0;
        Stack->Capacity = 0;
        return true;
    }
    /*
    就假设新的大小NewSize比现在的容量CurrentCapacity大就申请NewSize个元素的空间，如果小于就是收缩后就把多余的内存返回给操作系统了
    一样就直接放回原本的指针，直接no op了
    */
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
/**
 * @brief 将一帧精确压入调用栈。
 * @details 调用栈精确压入。每次压入都会触发一次精确扩容，使 Capacity 严格等于 Size。
 * @param[in,out] Stack 指向待操作的 FtraceFrameStack 实例。
 * @param[in]     Frame 指向待压入的 FtraceFrame。
 * @return 压入成功返回 true；若 Stack 或 Frame 为 NULL，或扩容失败则返回 false。
 */
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
    // 写数据了
    Stack->Data[NewSize - 1] = *Frame;
    return true;
}
/**
 * @brief 从调用栈精确弹出一帧。
 * @details 调用栈精确弹出。每次弹出都会触发一次精确缩容，使 Capacity 严格等于 Size。
 * @param[in,out] Stack 指向待操作的 FtraceFrameStack 实例。
 * @param[out]    Out   用于接收弹出的栈顶帧，可为 NULL（仅弹出而不保留）。
 * @return 弹出成功返回 true；若 Stack 为 NULL 或栈空则返回 false。
 */
static bool FtraceFrameStackPopExact(FtraceFrameStack *Stack, FtraceFrame *Out)
{
    if (Stack == NULL || Stack->Size == 0)
    {
        return false;
    }
    // 先保存栈顶的元素，主要是一位内后面用RE缩容的话，就是如果RE执行完，可能，就是可能S->D的指针会失效，就在缩小前拿出来数据
    FtraceFrame Top = Stack->Data[Stack->Size - 1];
    // 先标记一下，防止忘记，就是假设目前是有size个元素，然后去除一个后，大小就是size-1
    size_t NewSize = Stack->Size - 1;
    if (!FtraceFrameStackResizeExact(Stack, NewSize))
    {
        return false;
    }
    if (Out != NULL)
    {
        *Out = Top; // 把保存的数据回传给调用的那边
    }
    return true;
}
/**
 * @brief 精确调整事件向量容量，使其与实际使用大小完全匹配。
 * @details 事件向量精确调整大小。调用后 Vector->Capacity 将严格等于 NewSize。
 *          当 NewSize 为 0 时，会彻底释放 Vector->Data 并将所有字段归零。
 * @param[in,out] Vector  指向待调整的 FtraceEventVector 实例。
 * @param[in]     NewSize 目标元素个数，同时也是新的总容量。
 * @return 调整成功返回 true；若 Vector 为 NULL 或 realloc 失败则返回 false。
 */
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
/**
 * @brief 将一个事件精确追加到事件向量尾部。
 * @details 事件向量精确追加。每次追加都会触发一次精确扩容，使 Capacity 严格等于 Size。
 * @param[in,out] Vector 指向待操作的 FtraceEventVector 实例。
 * @param[in]     Event  指向待追加的 FtraceEvent。
 * @return 追加成功返回 true；若 Vector 或 Event 为 NULL，或扩容失败则返回 false。
 */
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
/**
 * @brief 初始化 ftrace 状态结构体。
 * @details 先手动清零，防止出现意外，然后再手动设置值。
 *          默认关闭 ftrace，但默认记录历史事件。不预分配动态内存。
 * @param[in,out] State 指向待初始化的 FtraceState 实例。
 * @return 初始化成功返回 true；若 State 为 NULL 则返回 false。
 */
bool FtraceStateInit(FtraceState *State)
{
    if (State == NULL)
    {
        return false;
    }
    // 先手动清零，防止出现意外，然后再手动设置值
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
/**
 * @brief 销毁 ftrace 状态，释放其内部动态内存。
 * @param[in,out] State 指向待销毁的 FtraceState 实例，若为 NULL 则不做任何操作。
 */
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
/**
 * @brief 重置 ftrace 状态，清空调用栈和历史记录，但保留状态结构体本身。
 * @param[in,out] State 指向待重置的 FtraceState 实例，若为 NULL 则不做任何操作。
 */
void FtraceStateReset(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    FtraceFrameStackResizeExact(&State->CallStack, 0);
    FtraceEventVectorResizeExact(&State->History, 0);
}
/**
 * @brief 开启 ftrace 记录。
 * @param[in,out] State 指向待操作的 FtraceState 实例，若为 NULL 则不做任何操作。
 */
void FtraceEnable(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    State->Enabled = true;
}
/**
 * @brief 关闭 ftrace 记录。
 * @param[in,out] State 指向待操作的 FtraceState 实例，若为 NULL 则不做任何操作。
 */
void FtraceDisable(FtraceState *State)
{
    if (State == NULL)
    {
        return;
    }
    State->Enabled = false;
}
/**
 * @brief 查询 ftrace 当前是否已开启。
 * @param[in] State 指向待查询的 FtraceState 实例。
 * @return 已开启返回 true；若 State 为 NULL 或已关闭则返回 false。
 */
bool FtraceIsEnabled(const FtraceState *State)
{
    if (State == NULL)
    {
        return false;
    }
    return State->Enabled;
}
/**
 * @brief 设置是否记录完整历史事件序列。
 * @param[in,out] State         指向待操作的 FtraceState 实例，若为 NULL 则不做任何操作。
 * @param[in]     RecordHistory 为 true 时记录历史，为 false 时仅打印不保存。
 */
void FtraceSetRecordHistory(FtraceState *State, bool RecordHistory)
{
    if (State == NULL)
    {
        return;
    }
    State->RecordHistory = RecordHistory;
}
/**
 * @brief 处理一次函数调用事件。
 * @details 若 ftrace 已启用，会将调用帧压入调用栈，并打印/记录本次 call 事件。
 * @param[in,out] State           指向 ftrace 状态实例。
 * @param[in]     CallPC          调用指令所在的程序计数器。
 * @param[in]     FunctionAddress 被调用函数的入口地址。
 * @return 处理成功返回 true；若 State 为 NULL 或内部操作失败则返回 false。
 *         若 ftrace 未启用，也返回 true（表示无需处理）。
 */
bool FtraceOnCall(FtraceState *State, vaddr_t CallPC, vaddr_t FunctionAddress)
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
    Frame.CallPC = CallPC;
    Frame.ReturnPC = CallPC + 4;
    Frame.FunctionAddress = FunctionAddress;
    Frame.FunctionName = ReadelfFindFunctionName(FunctionAddress);
    if (!FtraceFrameStackPushExact(&State->CallStack, &Frame))
    {
        return false;
    }
    FtraceEvent Event;
    Event.Type = FtraceEventCall;
    Event.CurrentPC = CallPC;
    Event.TargetPC = FunctionAddress;
    Event.FunctionName = Frame.FunctionName;
    Event.Depth = State->CallStack.Size;
    FtracePrintEventLine(&Event);
    if (State->RecordHistory)
    {
        if (!FtraceEventVectorPushExact(&State->History, &Event))
        {
            return false;
        }
    }
    return true;
}
/**
 * @brief 处理一次函数返回事件。
 * @details 若 ftrace 已启用，会从调用栈弹出一帧，并打印/记录本次 ret 事件。
 * @param[in,out] State     指向 ftrace 状态实例。
 * @param[in]     CurrentPc 返回指令所在的程序计数器。
 * @param[in]     TargetPc  返回目标地址。
 * @return 处理成功返回 true；若 State 为 NULL、栈空或内部操作失败则返回 false。
 *         若 ftrace 未启用，也返回 true（表示无需处理）。
 */
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
    FtraceEvent Event;
    Event.Type = FtraceEventRet;
    Event.CurrentPC = CurrentPc;
    Event.TargetPC = TargetPc;
    Event.FunctionName = Frame.FunctionName;
    Event.Depth = State->CallStack.Size;
    FtracePrintEventLine(&Event);
    if (State->RecordHistory)
    {
        if (!FtraceEventVectorPushExact(&State->History, &Event))
        {
            return false;
        }
    }
    return true;
}
/**
 * @brief 获取当前函数调用深度（调用栈大小）。
 * @param[in] State 指向 ftrace 状态实例。
 * @return 当前调用深度；若 State 为 NULL 则返回 0。
 */
size_t FtraceGetDepth(const FtraceState *State)
{
    if (State == NULL)
    {
        return 0;
    }
    return State->CallStack.Size;
}
/**
 * @brief 获取调用栈顶帧。
 * @param[in] State 指向 ftrace 状态实例。
 * @return 指向栈顶帧的指针；若 State 为 NULL 或栈空则返回 NULL。
 */
const FtraceFrame *FtraceGetTopFrame(const FtraceState *State)
{
    if (State == NULL || State->CallStack.Size == 0)
    {
        return NULL;
    }
    return &State->CallStack.Data[State->CallStack.Size - 1];
}
/**
 * @brief 获取最近一条记录的历史事件。
 * @param[in] State 指向 ftrace 状态实例。
 * @return 指向最近事件的指针；若 State 为 NULL 或无历史记录则返回 NULL。
 */
const FtraceEvent *FtraceGetLastEvent(const FtraceState *State)
{
    if (State == NULL || State->History.Size == 0)
    {
        return NULL;
    }
    return &State->History.Data[State->History.Size - 1];
}
/**
 * @brief 根据缩进层级打印对应数量的空格。
 * @param[in] Level 缩进层级，每层为两个空格。
 */
static void FtracePrintIndent(size_t Level)
{
    for (size_t i = 0; i < Level; i++)
    {
        printf("  ");
    }
}
/**
 * @brief 打印单条 ftrace 事件到标准输出。
 * @param[in] Event 指向待打印的 FtraceEvent，若为 NULL 则不做任何操作。
 */
static void FtracePrintEventLine(const FtraceEvent *Event)
{
    if (Event == NULL)
    {
        return;
    }
    // ？？？是在没有函数名的时候的占位符
    const char *FunctionName = Event->FunctionName ? Event->FunctionName : "???";
    // 一句话，如果是call缩进就是depth-1，因为现在还没有进入下一层，所以要和上一层对齐，call是进入新一层前的动作，然后ret
    // 就是直接depth，因为返回后的栈的深度已经剪掉了，直接用当前的这个深度对齐
    size_t IndentLevel = (Event->Type == FtraceEventCall) ? (Event->Depth > 0 ? Event->Depth - 1 : 0) : Event->Depth;
    printf(FMT_WORD ": ", Event->CurrentPC);
    FtracePrintIndent(IndentLevel);
    if (Event->Type == FtraceEventCall)
    {
        printf("call [%s@" FMT_WORD "]\n", FunctionName, Event->TargetPC);
    }
    else
    {
        printf("ret  [%s]\n", FunctionName);
    }
    // 直接刷新输出缓冲区
    fflush(stdout);
}
/**
 * @brief 打印当前函数调用栈到标准输出。
 * @param[in] State 指向 ftrace 状态实例，若为 NULL 则打印错误提示。
 */
void FtracePrintCurrentStack(const FtraceState *State)
{
    if (State == NULL)
    {
        printf("ftrace: 状态指针为空\n");
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
/**
 * @brief 打印所有已记录的历史事件到标准输出。
 * @param[in] State 指向 ftrace 状态实例，若为 NULL 则打印错误提示。
 */
void FtracePrintHistory(const FtraceState *State)
{
    if (State == NULL)
    {
        printf("ftrace: 状态指针为空\n");
        return;
    }
    printf("ftrace: history size = %zu\n", State->History.Size);
    for (size_t i = 0; i < State->History.Size; i++)
    {
        FtracePrintEventLine(&State->History.Data[i]);
    }
}
/**
 * @brief 打印 ftrace 当前运行状态摘要到标准输出。
 * @param[in] State 指向 ftrace 状态实例，若为 NULL 则打印错误提示。
 */
void FtracePrintStatus(const FtraceState *State)
{
    if (State == NULL)
    {
        printf("ftrace: 状态指针为空\n");
        return;
    }
    printf("ftrace: enabled=%s, record_history=%s, depth=%zu, history=%zu\n",
           State->Enabled ? "true" : "false",
           State->RecordHistory ? "true" : "false",
           State->CallStack.Size,
           State->History.Size);
}

#endif
