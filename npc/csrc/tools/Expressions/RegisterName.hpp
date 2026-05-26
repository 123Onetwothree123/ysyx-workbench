#ifndef REGISTER_NAME_HPP
#define REGISTER_NAME_HPP
#include <cstdint>
#include <optional>
#include <string_view>
[[nodiscard]] std::string_view StripRegisterPrefix(std::string_view Name);
[[nodiscard]] bool IsProgramCounterName(std::string_view Name);
[[nodiscard]] std::optional<std::uint32_t> RegisterNameToIndex(std::string_view Name);
#endif
