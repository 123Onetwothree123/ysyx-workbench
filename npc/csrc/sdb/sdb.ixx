export module npc.sdb.sdb;
import std;
import npc.DUT;

export class SDB final
{
public:
    SDB() = delete;
    static void MainLoop(DUT &dut, bool batch_mode = false); // batch_mode直接等于就是c命令，直接自动执行到结束
};
