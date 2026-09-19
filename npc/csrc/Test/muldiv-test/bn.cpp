#include "bn.hpp"

namespace bn
{

namespace
{
constexpr uint32_t BASE = 0x10000u;
constexpr uint32_t MASK = 0xFFFFu;

// Knuth算法D长除法(16位limb): u/v -> q, rem
// u最多WIDE_LIMBS个limb, v最多LIMBS个limb; 中间关键运算是32位整除/乘法
bool divmod_core(uint32_t q[WIDE_LIMBS], uint32_t rem[LIMBS],
                 const uint32_t u[WIDE_LIMBS], int ulen,
                 const uint32_t v[LIMBS], int vlen)
{
    for (int i = 0; i < WIDE_LIMBS; ++i)
    {
        q[i] = 0;
    }
    for (int i = 0; i < LIMBS; ++i)
    {
        rem[i] = 0;
    }
    while (ulen > 1 && u[ulen - 1] == 0)
    {
        --ulen;
    }
    while (vlen > 1 && v[vlen - 1] == 0)
    {
        --vlen;
    }
    if (v[vlen - 1] == 0)
    {
        return false; // 除零
    }
    if (ulen < vlen)
    {
        for (int i = 0; i < ulen; ++i)
        {
            rem[i] = u[i];
        }
        return true;
    }
    if (vlen == 1)
    {
        uint32_t r = 0;
        for (int i = ulen - 1; i >= 0; --i)
        {
            const uint32_t cur = (r << 16) | u[i];
            q[i] = cur / v[0];
            r = cur % v[0];
        }
        rem[0] = r;
        return true;
    }
    // 归一化: 把除数最高limb左移成>=0x8000
    int s = 0;
    while (((v[vlen - 1] << s) & 0x8000u) == 0)
    {
        ++s;
    }
    uint32_t un[WIDE_LIMBS + 1] = {};
    uint32_t vn[LIMBS] = {};
    uint32_t carry = 0;
    for (int i = 0; i < ulen; ++i)
    {
        const uint32_t t = (u[i] << s) | carry;
        un[i] = t & MASK;
        carry = t >> 16;
    }
    un[ulen] = carry;
    carry = 0;
    for (int i = 0; i < vlen; ++i)
    {
        const uint32_t t = (v[i] << s) | carry;
        vn[i] = t & MASK;
        carry = t >> 16;
    }
    const int n = vlen;
    for (int j = ulen - n; j >= 0; --j)
    {
        uint32_t qhat = 0;
        uint32_t rhat = 0;
        const uint32_t num_hi = un[j + n];
        const uint32_t num_lo = un[j + n - 1];
        // 估计商位: num_hi<=vn[n-1]时不变量保证 qhat<=基数+1, 交给修正循环收敛
        // (raht是除法余数, 一定<vn[n-1]; 64位中间量避免num_hi==vn[n-1]时溢出)
        const uint32_t cur = (num_hi << 16) | num_lo;
        qhat = cur / vn[n - 1];
        rhat = cur % vn[n - 1];
        bool settled = false;
        while (!settled)
        {
            if (qhat >= BASE ||
                static_cast<uint64_t>(qhat) * vn[n - 2] >
                    (static_cast<uint64_t>(rhat) << 16) + un[j + n - 2])
            {
                qhat -= 1;
                rhat += vn[n - 1];
                settled = (rhat >= BASE);
            }
            else
            {
                settled = true;
            }
        }
        // u[j..j+n] -= qhat * vn
        uint32_t borrow = 0;
        uint32_t mul_carry = 0;
        for (int i = 0; i < n; ++i)
        {
            const uint32_t p = qhat * vn[i] + mul_carry;
            mul_carry = p >> 16;
            const uint32_t subtrahend = (p & MASK) + borrow;
            if (un[i + j] < subtrahend)
            {
                un[i + j] = un[i + j] + BASE - subtrahend;
                borrow = 1;
            }
            else
            {
                un[i + j] -= subtrahend;
                borrow = 0;
            }
        }
        const uint32_t top = mul_carry + borrow;
        if (un[j + n] < top)
        {
            un[j + n] = un[j + n] + BASE - top;
            borrow = 1;
        }
        else
        {
            un[j + n] -= top;
            borrow = 0;
        }
        if (borrow)
        {
            // qhat过大, 回加一个除数
            qhat -= 1;
            uint32_t c = 0;
            for (int i = 0; i < n; ++i)
            {
                const uint32_t t = un[i + j] + vn[i] + c;
                un[i + j] = t & MASK;
                c = t >> 16;
            }
            un[j + n] = (un[j + n] + c) & MASK;
        }
        q[j] = qhat;
    }
    // 反归一化余数
    if (s == 0)
    {
        for (int i = 0; i < n; ++i)
        {
            rem[i] = un[i];
        }
    }
    else
    {
        uint32_t keep = 0;
        for (int i = n - 1; i >= 0; --i)
        {
            const uint32_t cur = (keep << 16) | un[i];
            rem[i] = cur >> s;
            keep = cur & ((1u << s) - 1u);
        }
    }
    return true;
}
} // namespace

Num make(const uint32_t words[4])
{
    Num r;
    from_words(r, words);
    return r;
}

Num make_u64(uint64_t x)
{
    Num r;
    from_u64(r, x);
    return r;
}

void zero(Num &r)
{
    for (int i = 0; i < LIMBS; ++i)
    {
        r.v[i] = 0;
    }
}

void from_words(Num &r, const uint32_t words[4])
{
    for (int i = 0; i < 4; ++i)
    {
        r.v[2 * i] = words[i] & MASK;
        r.v[2 * i + 1] = (words[i] >> 16) & MASK;
    }
}

void from_u64(Num &r, uint64_t x)
{
    for (int i = 0; i < LIMBS; ++i)
    {
        r.v[i] = static_cast<uint32_t>(x & MASK);
        x >>= 16;
    }
}

uint64_t to_u64(const Num &a)
{
    uint64_t r = 0;
    for (int i = 3; i >= 0; --i)
    {
        r = (r << 16) | a.v[i];
    }
    return r;
}

uint32_t to_u32(const Num &a)
{
    return a.v[0] | (a.v[1] << 16);
}

bool is_zero(const Num &a)
{
    for (int i = 0; i < LIMBS; ++i)
    {
        if (a.v[i] != 0)
        {
            return false;
        }
    }
    return true;
}

bool equal(const Num &a, const Num &b)
{
    for (int i = 0; i < LIMBS; ++i)
    {
        if (a.v[i] != b.v[i])
        {
            return false;
        }
    }
    return true;
}

int compare(const Num &a, const Num &b)
{
    for (int i = LIMBS - 1; i >= 0; --i)
    {
        if (a.v[i] != b.v[i])
        {
            return a.v[i] < b.v[i] ? -1 : 1;
        }
    }
    return 0;
}

bool is_even(const Num &a)
{
    return (a.v[0] & 1u) == 0;
}

int bit_length(const Num &a)
{
    for (int i = LIMBS - 1; i >= 0; --i)
    {
        if (a.v[i] != 0)
        {
            int bits = 0;
            for (uint32_t x = a.v[i]; x != 0; x >>= 1)
            {
                ++bits;
            }
            return i * 16 + bits;
        }
    }
    return 0;
}

bool get_bit(const Num &a, int index)
{
    if (index < 0 || index >= LIMBS * 16)
    {
        return false;
    }
    return ((a.v[index >> 4] >> (index & 15)) & 1u) != 0;
}

void add(Num &r, const Num &a, const Num &b)
{
    uint32_t carry = 0;
    for (int i = 0; i < LIMBS; ++i)
    {
        const uint32_t t = a.v[i] + b.v[i] + carry;
        r.v[i] = t & MASK;
        carry = t >> 16;
    }
}

void sub(Num &r, const Num &a, const Num &b)
{
    uint32_t borrow = 0;
    for (int i = 0; i < LIMBS; ++i)
    {
        const uint32_t subtrahend = b.v[i] + borrow;
        if (a.v[i] < subtrahend)
        {
            r.v[i] = a.v[i] + BASE - subtrahend;
            borrow = 1;
        }
        else
        {
            r.v[i] = a.v[i] - subtrahend;
            borrow = 0;
        }
    }
}

void shr1(Num &r, const Num &a)
{
    uint32_t carry = 0;
    for (int i = LIMBS - 1; i >= 0; --i)
    {
        const uint32_t t = a.v[i];
        r.v[i] = (t >> 1) | (carry << 15);
        carry = t & 1u;
    }
}

void mul_low(Num &r, const Num &a, const Num &b)
{
    uint32_t acc[LIMBS] = {};
    for (int i = 0; i < LIMBS; ++i)
    {
        uint32_t carry = 0;
        for (int j = 0; i + j < LIMBS; ++j)
        {
            const uint32_t t = acc[i + j] + a.v[i] * b.v[j] + carry;
            acc[i + j] = t & MASK;
            carry = t >> 16;
        }
    }
    for (int i = 0; i < LIMBS; ++i)
    {
        r.v[i] = acc[i];
    }
}

void mul_wide(Wide &r, const Num &a, const Num &b)
{
    for (int i = 0; i < WIDE_LIMBS; ++i)
    {
        r.v[i] = 0;
    }
    for (int i = 0; i < LIMBS; ++i)
    {
        uint32_t carry = 0;
        for (int j = 0; j < LIMBS; ++j)
        {
            const uint32_t t = r.v[i + j] + a.v[i] * b.v[j] + carry;
            r.v[i + j] = t & MASK;
            carry = t >> 16;
        }
        r.v[i + LIMBS] = carry;
    }
}

bool divmod(Num &q, Num &r, const Num &a, const Num &b)
{
    uint32_t u[WIDE_LIMBS] = {};
    uint32_t qq[WIDE_LIMBS] = {};
    uint32_t rr[LIMBS] = {};
    for (int i = 0; i < LIMBS; ++i)
    {
        u[i] = a.v[i];
    }
    const bool ok = divmod_core(qq, rr, u, LIMBS, b.v, LIMBS);
    for (int i = 0; i < LIMBS; ++i)
    {
        q.v[i] = qq[i];
        r.v[i] = rr[i];
    }
    return ok;
}

void mod_wide(Num &r, const Wide &a, const Num &m)
{
    uint32_t qq[WIDE_LIMBS] = {};
    uint32_t rr[LIMBS] = {};
    divmod_core(qq, rr, a.v, WIDE_LIMBS, m.v, LIMBS);
    for (int i = 0; i < LIMBS; ++i)
    {
        r.v[i] = rr[i];
    }
}

void mulmod(Num &r, const Num &a, const Num &b, const Num &m)
{
    Wide p;
    mul_wide(p, a, b);
    mod_wide(r, p, m);
}

void modexp(Num &r, const Num &base, const Num &exponent, const Num &modulus)
{
    Num acc = make_u64(1);
    Num b;
    {
        Num q;
        divmod(q, b, base, modulus);
    }
    const int bits = bit_length(exponent);
    for (int i = bits - 1; i >= 0; --i)
    {
        Num t;
        mulmod(t, acc, acc, modulus);
        acc = t;
        if (get_bit(exponent, i))
        {
            mulmod(t, acc, b, modulus);
            acc = t;
        }
    }
    r = acc;
}

void gcd(Num &r, const Num &a, const Num &b)
{
    Num x = a;
    Num y = b;
    while (!is_zero(y))
    {
        Num q;
        Num t;
        divmod(q, t, x, y);
        x = y;
        y = t;
    }
    r = x;
}

bool mod_inverse(Num &r, const Num &a, const Num &m)
{
    Num old_r = a;
    Num cur_r = m;
    Num old_s = make_u64(1);
    Num cur_s = make_u64(0);
    while (!is_zero(cur_r))
    {
        Num q;
        Num t;
        divmod(q, t, old_r, cur_r);
        old_r = cur_r;
        cur_r = t;
        // (old_s, cur_s) = (cur_s, old_s - q*cur_s mod m)
        Num q_mod; // q mod m
        {
            Num quotient;
            divmod(quotient, q_mod, q, m);
        }
        Num term;
        mulmod(term, q_mod, cur_s, m);
        Num next_s;
        if (compare(old_s, term) >= 0)
        {
            sub(next_s, old_s, term);
        }
        else
        {
            add(next_s, old_s, m);
            sub(next_s, next_s, term);
        }
        old_s = cur_s;
        cur_s = next_s;
    }
    if (compare(old_r, make_u64(1)) != 0)
    {
        return false;
    }
    r = old_s;
    return true;
}

bool is_probably_prime(const Num &n, int rounds)
{
    const Num two = make_u64(2);
    if (compare(n, two) < 0)
    {
        return false;
    }
    if (equal(n, two))
    {
        return true;
    }
    if (is_even(n))
    {
        return false;
    }
    const Num one = make_u64(1);
    Num n_minus_1;
    sub(n_minus_1, n, one);
    Num d = n_minus_1;
    int s = 0;
    while (is_even(d))
    {
        shr1(d, d);
        ++s;
    }
    static const uint32_t small_bases[] = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
    const int used = rounds < 12 ? rounds : 12;
    for (int k = 0; k < used; ++k)
    {
        const Num base = make_u64(small_bases[k]);
        if (compare(base, n) >= 0)
        {
            continue;
        }
        Num x;
        modexp(x, base, d, n);
        if (equal(x, one) || equal(x, n_minus_1))
        {
            continue;
        }
        bool possibly_prime = false;
        for (int i = 1; i < s; ++i)
        {
            Num t;
            mulmod(t, x, x, n);
            x = t;
            if (equal(x, n_minus_1))
            {
                possibly_prime = true;
                break;
            }
        }
        if (!possibly_prime)
        {
            return false;
        }
    }
    return true;
}

void lcg_next(Num &state, Num &out)
{
    const Num a = make_u64(0x2545F4914F6CDD1Dull);
    const Num c = make_u64(0x9E3779B97F4A7C15ull);
    Num t;
    mul_low(t, state, a);
    add(state, t, c);
    out = state;
}

uint32_t hash(const Num &a)
{
    uint32_t h = 2166136261u;
    for (int i = 0; i < LIMBS; ++i)
    {
        h = (h ^ a.v[i]) * 16777619u;
    }
    return h;
}

uint32_t hash_wide(const Wide &a)
{
    uint32_t h = 2166136261u;
    for (int i = 0; i < WIDE_LIMBS; ++i)
    {
        h = (h ^ a.v[i]) * 16777619u;
    }
    return h;
}

} // namespace bn
