#ifndef EVALUATION_CONTEXT_HPP
#define EVALUATION_CONTEXT_HPP
#include <cstddef>
#include <cstdint>
#include <string>
class EvaluationContext
{
public:
    virtual ~EvaluationContext() = default;
    virtual std::uint32_t ReadRegister(std::string_view name) const = 0;
    virtual std::uint32_t ReadMemory(std::uint32_t address, std::size_t size) const = 0;
    virtual std::uint32_t GetPC() const = 0;
};
#endif