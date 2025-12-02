/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <isa.h>

/* We use the POSIX regex functions to process regular expressions.
 * Type 'man regex' for more information about POSIX regex functions.
 */
#include <regex.h>

#include <stdbool.h>
#include <memory/paddr.h>
#include <common.h>
#include <../../../src/isa/riscv32/local-include/reg.h>
#include "sdb.h"
#include <cpu/cpu.h>
static bool eval_success = true;
static void identify_unary_operators();
int find_main_operator(int p, int q);
static inline bool is_operator_token(int type);
static bool validate_parentheses();
static bool is_enclosed_in_parentheses(int p, int q);
typedef struct
{
  int precedence;
  bool is_right_assoc;
} OperatorInfo;
typedef enum
{
  EXP_OK = 0,
  EXP_SYNTAX,   // Syntax/lexical error
  EXP_PAREN,    // Parenthesis mismatch
  EXP_DIV_ZERO, // Division by zero
  EXP_BAD_REG,  // Invalid register
  EXP_BAD_MEM,  // Invalid address
  EXP_UNKNOWN
} ExprError;
static ExprError g_internal_error = EXP_OK;
const char *expr_get_error_msg()
{
  switch (g_internal_error)
  {
  case EXP_SYNTAX:
    return "\033[1;31mSyntax Error\033[0m"; // red
  case EXP_PAREN:
    return "\033[1;31mParen Error\033[0m";
  case EXP_DIV_ZERO:
    return "\033[1;31mDiv By Zero\033[0m";
  case EXP_BAD_REG:
    return "\033[1;31mInvalid Reg\033[0m";
  case EXP_BAD_MEM:
    return "\033[1;31mInvalid Addr\033[0m";
  default:
    return "\033[1;31mUnknown Err\033[0m";
  }
}
sword_t eval(int, int);
enum
{
  TK_NOTYPE = 256,
  TK_EQ,

  /* TODO: Add more token types */
  // https://ysyx.oscc.cc/docs/ics-pa/1.5.html#%E6%95%B0%E5%AD%A6%E8%A1%A8%E8%BE%BE%E5%BC%8F%E6%B1%82%E5%80%BC
  TK_NUM,     // Decimal System, 10
  TK_HEX,     // Hexadecimal, 16
  TK_REG,     // Register
  TK_MINUS,   // negative sign or Sign for Subtraction
  TK_POINTER, // Dereference Operator for Pointers
  TK_NEQ,     //!=, Not equal to
  TK_LE,      // <=
  TK_AND,     // &&
};

static struct rule
{
  const char *regex;
  int token_type;
} rules[] = {

    /* TODO: Add more rules.
     * Pay attention to the precedence level of different rules.
     */

    {" +", TK_NOTYPE},           // spaces
    {"\\+", '+'},                // plus
    {"==", TK_EQ},               // equal
    {"!=", TK_NEQ},              // not equal
    {"\\*", '*'},                // multiply
    {"/", '/'},                  // divide
    {"-", '-'},                  // minus
    {"\\(", '('},                // left paren
    {"\\)", ')'},                // right paren
    {"0x[0-9a-fA-F]+", TK_HEX},  // hexadecimal
    {"[0-9]+", TK_NUM},          // decimal
    {"\\$[a-zA-Z0-9]+", TK_REG}, // register
    {"<=", TK_LE},               // less equal
    {"&&", TK_AND},              // logical and
};
static inline OperatorInfo get_op_info(int type)
{
  switch (type)
  {
  // left
  case '+':
    return (OperatorInfo){3, false};
  case '-':
    return (OperatorInfo){3, false};
  case '*':
    return (OperatorInfo){4, false};
  case '/':
    return (OperatorInfo){4, false};
  case TK_EQ:
    return (OperatorInfo){2, false};
  case TK_NEQ:
    return (OperatorInfo){2, false};
  case TK_LE:
    return (OperatorInfo){2, false};
  case TK_AND:
    return (OperatorInfo){1, false};
  // right
  case TK_MINUS:
    return (OperatorInfo){5, true};
  case TK_POINTER:
    return (OperatorInfo){5, true};
  default:
    return (OperatorInfo){0, false}; // default return false, because this is not operator
  }
}
// judge operator
static inline bool is_operator_token(int type)
{
  return get_op_info(type).precedence > 0 || type == '(' || type == ')';
}
#define NR_REGEX ARRLEN(rules)

static regex_t re[NR_REGEX] = {};

/* Rules are used for many times.
 * Therefore we compile them only once before any usage.
 */
void init_regex()
{
  int i;
  char error_msg[128];
  int ret;

  for (i = 0; i < NR_REGEX; i++)
  {
    ret = regcomp(&re[i], rules[i].regex, REG_EXTENDED);
    if (ret != 0)
    {
      regerror(ret, &re[i], error_msg, 128);
      panic("regex compilation failed: %s\n%s", error_msg, rules[i].regex);
    }
  }
}

typedef struct token
{
  int type;
  char str[32];
} Token;

static Token tokens[32] __attribute__((used)) = {};
static int nr_token __attribute__((used)) = 0;

static bool make_token(char *e)
{
  int position = 0;
  int i;
  regmatch_t pmatch;

  nr_token = 0;

  while (e[position] != '\0')
  {
    /* Try all rules one by one. */
    for (i = 0; i < NR_REGEX; i++)
    {
      if (regexec(&re[i], e + position, 1, &pmatch, 0) == 0 && pmatch.rm_so == 0)
      {
        char *substr_start = e + position;
        int substr_len = pmatch.rm_eo;

        Log("match rules[%d] = \"%s\" at position %d with len %d: %.*s",
            i, rules[i].regex, position, substr_len, substr_len, substr_start);

        position += substr_len;

        /* TODO: Now a new token is recognized with rules[i]. Add codes
         * to record the token in the array `tokens'. For certain types
         * of tokens, some extra actions should be performed.
         */
        if (rules[i].token_type != TK_NOTYPE && nr_token >= 32)
        {
          printf("Error: Expression too complex: maximum 32 tokens exceeded\n");
          return false;
        }
        switch (rules[i].token_type)
        {
        case TK_NUM:
        case TK_HEX:
        case TK_REG:
          if (substr_len >= sizeof(tokens[nr_token].str))
          {
            printf("Error: Token too long at position %d\n", position);
            return false;
          }
          strncpy(tokens[nr_token].str, substr_start, substr_len);
          tokens[nr_token].str[substr_len] = '\0';
          tokens[nr_token].type = rules[i].token_type;
          nr_token++;
          break;
        case TK_NOTYPE:
          break;
        default:
          // TODO();
          tokens[nr_token].type = rules[i].token_type;
          nr_token++;
          break;
        }

        break;
      }
    }

    if (i == NR_REGEX)
    {
      printf("no match at position %d\n%s\n%*.s^\n", position, e, position, "");
      return false;
    }
  }

  return true;
}

sword_t expr(char *e, bool *success)
{
  g_internal_error = EXP_OK;
  *success = false;
  if (!make_token(e))
  {
    *success = false;
    g_internal_error = EXP_SYNTAX;
    return 0;
  }

  /* TODO: Insert codes to evaluate the expression. */
  // TODO();
  identify_unary_operators(); // Identify and mark unary operators
  if (!validate_parentheses())
  {
    *success = false;
    g_internal_error = EXP_PAREN;
    return 0;
  }
  // If the check passed (ExcuteState is true), we can proceed with evaluation.
  // The eval function itself will handle other cases like single tokens, operators, etc.
  eval_success = true; // Reset and call eval
  sword_t result = eval(0, nr_token - 1);
  *success = eval_success;
  if (!eval_success && g_internal_error == EXP_OK)
  {
    g_internal_error = EXP_UNKNOWN;
  }
  return result;
}
int find_main_operator(int p, int q)
{
  if (p >= q)
  {
    return -1;
  }
  int op_pos = -1;          // main operator default is -1, meaning is not found
  int min_prec = INT32_MAX; // found lowest, default is higher than all the operators
  int paren_level = 0;      // The current nested level of parentheses
  for (int i = p; i <= q; i++)
  { // Handle parentheses, and update nested hierarchy
    int type = tokens[i].type;
    if (type == '(')
    {
      paren_level++;
      continue;
    }
    if (type == ')')
    {
      paren_level--;
      continue;
    }
    if (paren_level != 0)
    {
      continue;
    }
    if (type == TK_MINUS || type == TK_POINTER)
    {
      continue; // Skip the unary minus sign and unary dereference
    }
    OperatorInfo info = get_op_info(type);
    if (info.precedence == 0)
    {
      continue;
    }
    // kimi k2 turbo thinking:关键修复：左结合运算符(!is_right_assoc)应选择最右边的
    if (info.precedence < min_prec ||
        (info.precedence == min_prec && !info.is_right_assoc))
    {
      min_prec = info.precedence;
      op_pos = i;
    }
  }
  return op_pos;
}
sword_t eval(int p, int q)
{
  // invalid
  if (p > q)
  {
    printf("Error: Invalid expression range\n");
    eval_success = false;
    return 0;
  }
  else if (p == q)
  {
    // single token
    sword_t value = 0;
    switch (tokens[p].type)
    {
    case TK_NUM:
      value = (sword_t)strtol(tokens[p].str, NULL, 10);
      break;
    case TK_HEX:
      value = (sword_t)strtol(tokens[p].str, NULL, 16);
      break;
    case TK_REG:
    {
      // value = isa_reg_get(tokens[p].str + 1);
      bool reg_success = true;
      value = (sword_t)isa_reg_str2val(tokens[p].str + 1, &reg_success); // because tokens[p].str is "$ra", so we need send "ra"
      if (!reg_success)
      {
        // if fail
        printf("Error: Invalid register name '%s'\n", tokens[p].str);
        eval_success = false;
        return 0;
      }
      // value = isa_reg_str2val(tokens[p].str + 1, &success);
      break;
    }
    default:
      printf("Error: Invalid token type %d at position %d\n", tokens[p].type, p);
      eval_success = false;
      return 0;
      break;
    }
    return value;
  }
  if (is_enclosed_in_parentheses(p, q))
  {
    return eval(p + 1, q - 1);
  }
  int op_pos = find_main_operator(p, q);
  if (op_pos != -1)
  {
    // [Short-circuit evaluation] Check before evaluating the right operand
    if (tokens[op_pos].type == TK_AND)
    {
      sword_t left_val = eval(p, op_pos - 1);
      if (!eval_success)
      {
        return 0;
      }
      if (left_val == 0)
      {
        return 0; // Left operand is false, short-circuit return
      }
      sword_t right_val = eval(op_pos + 1, q);
      if (!eval_success)
      {
        return 0;
      }
      return right_val != 0; // Left is true, return the truth value of the right operand
    }
    // [Non-short-circuit operator] Evaluate left and right operands first
    sword_t left_val = eval(p, op_pos - 1);
    if (!eval_success)
    {
      return 0;
    }
    sword_t right_val = eval(op_pos + 1, q);
    if (!eval_success)
    {
      return 0;
    }
    switch (tokens[op_pos].type)
    {
    case '+':
    {
      if ((right_val > 0 && left_val > INT32_MAX - right_val) ||
          (right_val < 0 && left_val < INT32_MIN - right_val))
      {
        printf("Error: Integer overflow in addition\n");
        eval_success = false;
        g_internal_error = EXP_SYNTAX;
        return 0;
      }
      return left_val + right_val;
    }
    case '-':
      return left_val - right_val;
    case '*':
      return left_val * right_val;
    case '/':
      if (right_val == 0)
      {
        printf("Error: Division by zero\n");
        eval_success = false;
        return 0;
      }
      return left_val / right_val;
    case TK_EQ:
      return left_val == right_val;
    case TK_NEQ:
      return left_val != right_val;
    case TK_LE:
      return left_val <= right_val;
    default:
      printf("Error: Unsupported binary operator %d\n", tokens[op_pos].type);
      eval_success = false;
      return 0;
    }
  }
  if (tokens[p].type == TK_MINUS)
  {
    sword_t val = eval(p + 1, q);
    if (!eval_success)
    {
      return 0;
    }
    return -val;
  }
  if (tokens[p].type == TK_POINTER)
  {
    sword_t addr_signed = eval(p + 1, q);
    if (!eval_success)
    {
      return 0;
    }
    word_t addr = (word_t)addr_signed;
    word_t value;
    if (!safe_paddr_read(addr, &value, sizeof(word_t)))
    {
      printf("Error: Cannot dereference pointer at address 0x%08x\n", addr);
      eval_success = false;
      g_internal_error = EXP_BAD_MEM;
      return 0;
    }
    return (sword_t)value;
  }
  printf("Error: Cannot evaluate expression from token %d to %d\n", p, q);
  eval_success = false;
  return 0;
}
static void identify_unary_operators()
{
  for (int i = 0; i < nr_token; i++)
  {
    if (tokens[i].type != '-' && tokens[i].type != '*')
    {
      continue;
    }
    bool is_unary = false;
    // if expr start
    if (i == 0)
    {
      is_unary = true;
    }
    else if (tokens[i - 1].type == '(')
    {
      is_unary = true;
    }
    // if a token before is a operator or left parenthesis
    else
    {
      int prev_type = tokens[i - 1].type;
      if (prev_type == '(' || (is_operator_token(prev_type) && prev_type != ')'))
      {
        is_unary = true;
      }
    }
    if (is_unary)
    {
      tokens[i].type = (tokens[i].type == '-') ? TK_MINUS : TK_POINTER;
      Log("Token %d: '%c' identified as unary %s", i,
          tokens[i].type == TK_MINUS ? '-' : '*',
          tokens[i].type == TK_MINUS ? "minus" : "pointer");
    }
  }
}
static bool validate_parentheses()
{
  int counter = 0;
  for (int i = 0; i < nr_token; i++)
  {
    if (tokens[i].type == '(')
    {
      counter++;
    }
    else if (tokens[i].type == ')')
    {
      counter--;
      if (counter < 0)
      {
        printf("Error: Unmatched closing parenthesis at token %d\n", i);
        return false;
      }
    }
  }
  if (counter != 0)
  {
    printf("Error: %d unmatched opening parenthesis(es)\n", counter);
    return false;
  }
  return true;
}
static bool is_enclosed_in_parentheses(int p, int q)
{
  if (p >= q)
  {
    return false;
  }
  if (tokens[p].type != '(' || tokens[q].type != ')')
  {
    return false;
  }
  int counter = 1; // Start matching from p+1
  for (int i = p + 1; i < q; i++)
  {
    if (tokens[i].type == '(')
    {
      counter++;
    }
    else if (tokens[i].type == ')')
    {
      counter--;
    }
    if (counter == 0) // It is closed before q and is not the outermost layer
    {
      return false;
    }
  }
  return counter == 1;
}