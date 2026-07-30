module AccessRecord;
import std;
AccessRecord::AccessRecord(std::uint32_t a, bool w)
    : addr{a}, is_write{w}
{
}
std::uint32_t AccessRecord::GetAddr() const
{
    return addr;
}
bool AccessRecord::GetIsWrite() const
{
    return is_write;
}
