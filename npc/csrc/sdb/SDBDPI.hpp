#ifndef SDB_DPI_HPP
#define SDB_DPI_HPP
#include <cstdint>
#include <string_view>
void SDBDPISetTopScope(std::string_view InstanceScope, std::string_view ModelName);// 设置DPI的scope，InstanceScope是实例的scope，ModelName是顶层模块的名字，这个函数会根据这两个参数来设置DPI的scope，这样就可以在DPI函数中直接访问到SDB模块了
std::uint32_t CPP_NPCGetGPR(std::int32_t RegNum);// 获取通用寄存器的值，RegNum是寄存器的编号，0-31，如果RegNum不合法就返回0
std::uint32_t CPP_NPCGetPC();
void PrintGPR();
void PrintWatchpoints();
#endif
