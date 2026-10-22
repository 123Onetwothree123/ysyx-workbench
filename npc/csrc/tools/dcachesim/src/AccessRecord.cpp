module AccessRecord;
import std;
AccessRecord::AccessRecord(Addr a, bool w)
    : addr{a}, is_write{w}
{
}
Addr AccessRecord::GetAddr() const
{
    return addr;
}
bool AccessRecord::GetIsWrite() const
{
    return is_write;
}
