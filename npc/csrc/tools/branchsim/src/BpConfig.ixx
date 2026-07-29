export module BpConfig;
import std;
export class BpConfig
{
private:
    std::size_t btb_bits{static_cast<std::size_t>(BTB_BITS)}; // 表项数=2^N
    bool AlwaysJump{static_cast<bool>(0)};
    bool BTFN{static_cast<bool>(0)};
    //其他算法，等到时候会会再说吧
public:

};