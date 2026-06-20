module npc.trace.ReadelfFunction;
std::size_t ReadelfFunction::size() const noexcept
{
    return end - start;
}
bool ReadelfFunction::contains(std::size_t address) const noexcept
{
    return start <= address && address < end;
}
