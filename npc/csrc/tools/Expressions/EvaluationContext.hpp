#ifndef EVALUATION_CONTEXT_HPP
#define EVALUATION_CONTEXT_HPP
#include<string_view>
#include<print>
#include<cstddef>
#include<cstdint>
class EvaluationContext
{
public:
   virtual ~EvaluationContext()=default;
   virtual std::uint32_t ReadRegister(std::string_view Name) const = 0;
   virtual std::uint32_t ReadMemory(std::uint32_t Address,std::size_t Size) const = 0;
   virtual std::uint32_t GetProgramCounter() const = 0;
};
#endif
