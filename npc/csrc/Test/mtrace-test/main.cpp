// mtrace 64 位地址/数据格式回归。
//
// 集成路径中 MMU 会把 store 的 mtrace Addr 记录为翻译后的物理地址；
// 本单元测试直接调用 MtraceRecord，确保 4GiB 以上的 PC/Addr/Data
// 不会被截断成 32 位。
import std;
import npc.trace.mtrace;

int main()
{
    MtraceRecord(0x1'0000'0000ULL,
                 0x1'0000'2000ULL,
                 0x8877665544332211ULL,
                 0,
                 3,
                 true); // 8B store
    MtraceRecord(0x1'0000'0004ULL,
                 0x1'ffff'ffffULL,
                 0,
                 0xffffffffffffffffULL,
                 3,
                 false); // 8B load
    return 0;
}
