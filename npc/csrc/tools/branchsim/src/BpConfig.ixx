export module BpConfig;
import std;
export class BpConfig
{
private:
    std::size_t btb_bits{static_cast<std::size_t>(BTB_BITS)}; // 表项数=2^N
    
public:

};