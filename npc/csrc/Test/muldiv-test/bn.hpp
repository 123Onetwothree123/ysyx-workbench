// bn.hpp: 16位limb多倍精度整数(128位)
// 热路径只用32位以内的 * / 运算, 这样大整数算法在 RV32I(走libgcc软件例程)
// 和 RV32IM(走硬件M扩展) 上的差异会直接体现为耗时差异
#ifndef MULDIV_BN_HPP
#define MULDIV_BN_HPP

#include <cstdint>

namespace bn
{

inline constexpr int LIMBS = 8;       // 128位
inline constexpr int WIDE_LIMBS = 16; // 乘积/被除数宽度

struct Num
{
    uint32_t v[LIMBS];
};

struct Wide
{
    uint32_t v[WIDE_LIMBS];
};

Num make(const uint32_t words[4]); // words[0]是最低32位
Num make_u64(uint64_t x);

void zero(Num &r);
void from_words(Num &r, const uint32_t words[4]);
void from_u64(Num &r, uint64_t x);
uint64_t to_u64(const Num &a);
uint32_t to_u32(const Num &a);

bool is_zero(const Num &a);
bool equal(const Num &a, const Num &b);
int compare(const Num &a, const Num &b);
bool is_even(const Num &a);
int bit_length(const Num &a);
bool get_bit(const Num &a, int index);

void add(Num &r, const Num &a, const Num &b);
void sub(Num &r, const Num &a, const Num &b); // 模2^128减法
void shr1(Num &r, const Num &a);
void mul_low(Num &r, const Num &a, const Num &b);
void mul_wide(Wide &r, const Num &a, const Num &b);
bool divmod(Num &q, Num &r, const Num &a, const Num &b);
void mod_wide(Num &r, const Wide &a, const Num &m);
void mulmod(Num &r, const Num &a, const Num &b, const Num &m);
void modexp(Num &r, const Num &base, const Num &exponent, const Num &modulus);
void gcd(Num &r, const Num &a, const Num &b);
bool mod_inverse(Num &r, const Num &a, const Num &m);
bool is_probably_prime(const Num &n, int rounds);
void lcg_next(Num &state, Num &out);
uint32_t hash(const Num &a);
uint32_t hash_wide(const Wide &a);

} // namespace bn

#endif
