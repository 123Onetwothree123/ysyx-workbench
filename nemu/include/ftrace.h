#ifndef FTRACE_H
#define FTRACE_H
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <common.h>
typedef enum
{
    FtraceEventCall, // 函数调用事件
    FtraceEventRet,  // 函数返回事件
} FtraceEventType;
/*
栈帧，拿来描述一次函数调用的信息
*/
typedef struct
{
    vaddr_t CallPC;           // 调用指令所在的PC
    vaddr_t ReturnPC;         // 本次调用预期返回到的PC
    vaddr_t FunctionAddress;  // 被调用函数的入口地址
    const char *FunctionName; // 被调用函数的名字
} FtraceFrame;
typedef struct
{
    FtraceFrame *Data; // 动态数组首地址
    size_t Size;       // 当前已使用的元素个数
    size_t Capacity;   // 当前已分配的总容量
} FtraceFrameStack;    // 栈容器
typedef struct
{
    FtraceEventType Type;     // 当前事件类型，比如说call或ret
    vaddr_t CurrentPC;        // 当前事件对应指令的PC
    vaddr_t TargetPC;         // 当前事件跳转到的目标PC
    const char *FunctionName; // 当前事件对应的函数名
    size_t Depth;             // 当前事件的调用深度
} FtraceEvent;                // 历史事件，记录程序运行的时候产生的单词的call和ret时间的快照
typedef struct
{
    FtraceEvent *Data; // 历史事件动态数组首地址
    size_t Size;       // 当前已记录的事件数量
    size_t Capacity;   // 当前历史数组的总容量
} FtraceEventVector;   // 历史事件容器
typedef struct
{
    bool Enabled;               // 看这个ftrace是否启用
    bool RecordHistory;         // 是否记录完整历史事件
    FtraceFrameStack CallStack; // 当前函数调用栈
    FtraceEventVector History;  // 历史call和ret事件序列
} FtraceState;                  // 模块全局状态
#ifdef CONFIG_FTRACE
extern FtraceState GlobalFtraceState;
// 这是对外接口，模块生命周期部分
bool FtraceStateInit(FtraceState *State);    // 初始化 ftrace 状态，分配并准备内部动态数组
void FtraceStateDestroy(FtraceState *State); // 释放 ftrace 内部资源并清空状态
void FtraceStateReset(FtraceState *State);   // 清空调用栈和历史，但保留已初始化的分配
// 这是对外接口，开关控制部分
void FtraceEnable(FtraceState *State);                               // 开启 ftrace，后续 call/ret 会被记录和打印
void FtraceDisable(FtraceState *State);                              // 关闭 ftrace，后续 call/ret 直接忽略
bool FtraceIsEnabled(const FtraceState *State);                      // 返回当前 ftrace 是否开启
void FtraceSetRecordHistory(FtraceState *State, bool RecordHistory); // 设置是否记录历史事件
// 这是对外接口，指令事件入口的部分
bool FtraceOnCall(FtraceState *State, vaddr_t CallPC, vaddr_t FunctionAddress); // 处理一次函数调用事件并更新栈/历史
bool FtraceOnReturn(FtraceState *State, vaddr_t CurrentPc, vaddr_t TargetPc);   // 处理一次函数返回事件并更新栈/历史
// 这是对外接口，状态查询的部分
size_t FtraceGetDepth(const FtraceState *State);                 // 获取当前调用深度（栈大小）
const FtraceFrame *FtraceGetTopFrame(const FtraceState *State);  // 获取当前栈顶帧，若无则返回 NULL
const FtraceEvent *FtraceGetLastEvent(const FtraceState *State); // 获取最近一条历史事件，若无则返回 NULL
// 这是对外的接口，打印输出的部分
void FtracePrintCurrentStack(const FtraceState *State); // 打印当前调用栈（从底到顶）
void FtracePrintHistory(const FtraceState *State);      // 打印已记录的历史 call和ret 事件
void FtracePrintStatus(const FtraceState *State);       // 打印 ftrace 当前开关、深度与历史配置摘要
#else
#endif
#endif
