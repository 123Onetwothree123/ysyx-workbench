#ifndef SDB_HPP
#define SDB_HPP
#include "../DUT.hpp"
class DUT;
class SDB final
{
public:
    SDB() = delete;
    static void MainLoop(DUT &dut, bool batch_mode = false); // batch_mode直接等于就是c命令，直接自动执行到结束
};
#endif