// workload.cpp: 复杂算法负载的实现
// 覆盖的数学算法(全部只用+,-,*,/和比较/移位):
//   fib   : Fibonacci快速倍增 F(n) mod 2^128, 纯乘法依赖链
//   gcd   : Euclid辗转相除, 除法密集型
//   modexp: 费马小定理+指数可加性自检的模幂, 乘除混合
//   miller: Miller-Rabin素性测试(含强伪素数2047等陷阱用例)
//   rsa   : 用M31/M61两个梅森素数走一遍RSA密钥生成与加解密往返
#include "workload.hpp"
#include "bn.hpp"

namespace
{

using bn::Num;
using bn::Wide;

// 梅森素数(二进制全1)
const uint32_t W_M31[4] = {0x7FFFFFFFu, 0u, 0u, 0u};
const uint32_t W_M61[4] = {0xFFFFFFFFu, 0x1FFFFFFFu, 0u, 0u};
const uint32_t W_M89[4] = {0xFFFFFFFFu, 0xFFFFFFFFu, 0x01FFFFFFu, 0u};
const uint32_t W_M107[4] = {0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFFFFFFFu, 0x000007FFu};

Num constant(const uint32_t words[4])
{
    return bn::make(words);
}

// F(n)与F(n+1) mod 2^128: 快速倍增法, 每个bit做3次128位乘法
void fib_pair(Num &out_a, Num &out_b, uint32_t n)
{
    Num x = bn::make_u64(0);
    Num y = bn::make_u64(1);
    for (int i = 31; i >= 0; --i)
    {
        // c = F(2k) = F(k) * (2F(k+1) - F(k))
        // d = F(2k+1) = F(k)^2 + F(k+1)^2
        Num two_y;
        Num t;
        Num c;
        bn::add(two_y, y, y);
        bn::sub(t, two_y, x);
        bn::mul_low(c, x, t);
        Num xx;
        Num yy;
        Num d;
        bn::mul_low(xx, x, x);
        bn::mul_low(yy, y, y);
        bn::add(d, xx, yy);
        if (((n >> i) & 1u) != 0u)
        {
            bn::add(t, c, d);
            x = d;
            y = t;
        }
        else
        {
            x = c;
            y = d;
        }
    }
    out_a = x;
    out_b = y;
}

} // namespace

WorkResult run_phase_fib(int scale)
{
    WorkResult res{0u, 0};
    // 已知值校验: F(0..15)以及F(92), F(93)(u64能装下的最大两个)
    static const uint64_t known[] = {
        0ull, 1ull, 1ull, 2ull, 3ull, 5ull, 8ull, 13ull, 21ull, 34ull, 55ull, 89ull,
        144ull, 233ull, 377ull, 610ull, 7540113804746346429ull, 12200160415121876738ull};
    for (uint32_t n = 0; n < 16; ++n)
    {
        Num a;
        Num b;
        fib_pair(a, b, n);
        if (bn::to_u64(a) != known[n])
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(a);
    }
    {
        Num a;
        Num b;
        fib_pair(a, b, 92);
        if (bn::to_u64(a) != known[16])
        {
            ++res.failures;
        }
        fib_pair(a, b, 93);
        if (bn::to_u64(a) != known[17])
        {
            ++res.failures;
        }
    }
    // 大下标负载: 32位下标的快速倍增
    const int count = 4 * scale;
    Num a;
    Num b;
    for (int k = 0; k < count; ++k)
    {
        const uint32_t n = 2000000000u + static_cast<uint32_t>(k) * 2654435761u;
        fib_pair(a, b, n);
        res.checksum = res.checksum * 31u + bn::hash(a);
    }
    return res;
}

WorkResult run_phase_gcd(int scale)
{
    WorkResult res{0u, 0};
    Num state = bn::make_u64(0x243F6A8885A308D3ull);
    const int count = 3 * scale;
    for (int k = 0; k < count; ++k)
    {
        Num a;
        Num b;
        bn::lcg_next(state, a);
        bn::lcg_next(state, b);
        Num g;
        Num q;
        Num r;
        bn::gcd(g, a, b);
        // g必须同时整除a和b
        if (bn::divmod(q, r, a, g) && !bn::is_zero(r))
        {
            ++res.failures;
        }
        if (bn::divmod(q, r, b, g) && !bn::is_zero(r))
        {
            ++res.failures;
        }
        // gcd交换律
        Num g2;
        bn::gcd(g2, b, a);
        if (!bn::equal(g, g2))
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(g);
    }
    return res;
}

WorkResult run_phase_modexp(int scale)
{
    WorkResult res{0u, 0};
    Num state = bn::make_u64(0x9E3779B97F4A7C15ull);
    const Num one = bn::make_u64(1);
    const Num primes[2] = {constant(W_M61), constant(W_M89)};
    // 费马小定理: 素数m, gcd(a,m)=1 => a^(m-1) == 1 (mod m)
    for (int k = 0; k < 2; ++k)
    {
        const Num m = primes[k];
        Num a;
        bn::lcg_next(state, a);
        Num e;
        bn::sub(e, m, one);
        Num r;
        bn::modexp(r, a, e, m);
        if (!bn::equal(r, one))
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(r);
    }
    // 指数可加性: a^(e1+e2) == a^e1 * a^e2 (mod m)
    // 指数高位清零保证相加不溢出
    const Num m = constant(W_M61);
    for (int k = 0; k < scale; ++k)
    {
        Num a;
        Num e1;
        Num e2;
        bn::lcg_next(state, a);
        bn::lcg_next(state, e1);
        bn::lcg_next(state, e2);
        e1.v[7] &= 0x0FFFu;
        e2.v[7] &= 0x0FFFu;
        Num sum;
        bn::add(sum, e1, e2);
        Num lhs;
        bn::modexp(lhs, a, sum, m);
        Num t1;
        Num t2;
        Num rhs;
        bn::modexp(t1, a, e1, m);
        bn::modexp(t2, a, e2, m);
        bn::mulmod(rhs, t1, t2, m);
        if (!bn::equal(lhs, rhs))
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(lhs);
    }
    return res;
}

WorkResult run_phase_miller(int scale)
{
    WorkResult res{0u, 0};
    const int rounds = 2 + scale;
    const Num m31 = constant(W_M31);
    const Num m61 = constant(W_M61);
    const Num m89 = constant(W_M89);
    // 真素数(梅森素数)
    const Num primes[3] = {m31, m61, m89};
    for (int k = 0; k < 3; ++k)
    {
        if (!bn::is_probably_prime(primes[k], rounds))
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(primes[k]);
    }
    // 合数: 大素数乘积与一些经典陷阱数
    Num product;
    bn::mul_low(product, m31, m61);
    const Num composites[5] = {
        product, // M31*M61
        bn::make_u64(561ull),   // 最小Carmichael数
        bn::make_u64(1105ull),  // Carmichael数
        bn::make_u64(1729ull),  // Carmichael数(拉马努金数)
        bn::make_u64(2047ull),  // 23*89, 最小的基2强伪素数
    };
    for (int k = 0; k < 5; ++k)
    {
        if (bn::is_probably_prime(composites[k], rounds))
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(composites[k]);
    }
    // 再做一批大合数(素数的乘积)
    Num big_product;
    bn::mul_low(big_product, m61, m61);
    const Num big_composites[2] = {big_product, product};
    for (int k = 0; k < 2; ++k)
    {
        if (bn::is_probably_prime(big_composites[k], 1))
        {
            ++res.failures;
        }
        res.checksum = res.checksum * 31u + bn::hash(big_composites[k]);
    }
    return res;
}

WorkResult run_phase_rsa(int scale)
{
    WorkResult res{0u, 0};
    const Num p = constant(W_M61);
    const Num q = constant(W_M31);
    const Num one = bn::make_u64(1);
    // n = p*q
    Num n;
    bn::mul_low(n, p, q);
    // lambda = lcm(p-1, q-1) = (p-1)/gcd(p-1,q-1) * (q-1)
    Num p1;
    Num q1;
    bn::sub(p1, p, one);
    bn::sub(q1, q, one);
    Num g;
    bn::gcd(g, p1, q1);
    Num reduced;
    Num rem;
    {
        Num quotient;
        bn::divmod(quotient, rem, p1, g);
        bn::mul_low(reduced, quotient, q1);
    }
    // 私钥 d = e^{-1} mod lambda
    const Num e = bn::make_u64(65537ull);
    Num d;
    if (!bn::mod_inverse(d, e, reduced))
    {
        ++res.failures;
        d = one;
    }
    // 校验 d*e == 1 (mod lambda)
    Num check;
    bn::mulmod(check, d, e, reduced);
    if (!bn::equal(check, one))
    {
        ++res.failures;
    }
    // 加解密往返: (m^e)^d == m (mod n)
    const Num m = bn::make_u64(0x0123456789ABCDEFull);
    Num cipher;
    Num plain;
    bn::modexp(cipher, m, e, n);
    bn::modexp(plain, cipher, d, n);
    if (!bn::equal(plain, m))
    {
        ++res.failures;
    }
    // n必须是合数(Miller-Rabin快速确认)
    if (bn::is_probably_prime(n, 1))
    {
        ++res.failures;
    }
    res.checksum = bn::hash(n) * 31u + bn::hash(cipher);
    res.checksum = res.checksum * 31u + bn::hash(plain);
    if (scale > 1)
    {
        // 再跑几组模幂放大工作量(用不同的消息)
        Num state = bn::make_u64(0xDEADBEEFCAFEBABEull);
        for (int k = 1; k < scale; ++k)
        {
            Num msg;
            Num reduced_msg;
            bn::lcg_next(state, msg);
            {
                Num quotient;
                Num remainder;
                bn::divmod(quotient, remainder, msg, n);
                reduced_msg = remainder;
            }
            Num c2;
            Num p2;
            bn::modexp(c2, reduced_msg, e, n);
            bn::modexp(p2, c2, d, n);
            if (!bn::equal(p2, reduced_msg))
            {
                ++res.failures;
            }
            res.checksum = res.checksum * 31u + bn::hash(p2);
        }
    }
    return res;
}
