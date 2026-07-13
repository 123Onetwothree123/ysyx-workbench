#include "NVBoardSegmentStudentID.hpp"

void nvboard_segment_student_id(std::uintptr_t SegmentRegister)
{
    auto *const SEG{reinterpret_cast<volatile std::uint32_t *>(SegmentRegister)};
    std::uint32_t MarchID{};
    asm volatile("csrr %0, 0xF12" : "=r"(MarchID));
    *SEG = MarchID;
}
