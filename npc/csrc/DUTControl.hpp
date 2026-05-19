#ifndef DUT_CONTROL_HPP
#define DUT_CONTROL_HPP
#include <memory>
class VRV32E32Reg;
class DUTControl final
{
public:
    DUTControl();
    DUTControl(const DUTControl &) = delete;
    DUTControl &operator=(const DUTControl &) = delete;
    DUTControl(DUTControl &&) = delete;
    DUTControl &operator=(DUTControl &&) = delete;
    ~DUTControl();
    [[nodiscard]] VRV32E32Reg &GetTop() noexcept;
    [[nodiscard]] const VRV32E32Reg &GetTop() const noexcept;
    void Reset();
    void Final();
private:
    std::unique_ptr<VRV32E32Reg> Top;
    bool Finalized{false};
};
#endif
