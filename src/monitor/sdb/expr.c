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
bool needs_operator_decomposition(int p, int q);
int get_operator_precedence(int type);
int find_main_operator(int p, int q);
typedef struct
{
  bool result;
  bool ExcuteState;
} ExprResult;
ExprResult check_parenthese(int, int);
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

        switch (rules[i].token_type)
        {
        case TK_NUM:
        case TK_HEX:
        case TK_REG:
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
  *success = false;
  if (!make_token(e))
  {
    *success = false;
    return 0;
  }

  /* TODO: Insert codes to evaluate the expression. */
  // TODO();
  identify_unary_operators(); // Identify and mark unary operators
  ExprResult paren_check = check_parenthese(0, nr_token - 1);
  if (!paren_check.ExcuteState)
  {
    // If ExcuteState is false, it means there was a critical error during the check (e.g., mismatched parentheses).
    *success = false;
    return 0;
  }
  // If the check passed (ExcuteState is true), we can proceed with evaluation.
  // The eval function itself will handle other cases like single tokens, operators, etc.
  eval_success = true; // Reset and call eval
  sword_t result = eval(0, nr_token - 1);
  *success = eval_success;
  return result;
}

ExprResult check_parenthese(int p, int q)
{
  ExprResult result = {false, true};
  if (p > q)
  {
    // fail
    result.ExcuteState = false;
    return result;
  }
  if (p == q)
  {
    return result; // result keep false，ExcuteState keep true
  }
  if (tokens[p].type != '(' || tokens[q].type != ')')
  {
    return result; // result keep false，ExcuteState keep true
  }
  int counter = 0; // use counter check parentheses matche
  for (int i = p; i <= q; i++)
  {
    if (tokens[i].type == '(')
    {
      counter++;
    }
    else if (tokens[i].type == ')')
    {
      counter--;
    }
    // Check if the parentheses match
    if (counter < 0)
    {
      result.ExcuteState = false;
      return result;
    }
    // If the counter returns to zero before q, it means it's not the outermost parentheses
    if (counter == 0 && i < q)
    {
      return result; // result remains false, ExcuteState remains true
    }
  }
  if (counter == 0)
  {
    result.result = true;
  }
  else if (counter > 0)
  {
    printf("more left parenthesis than right parenthesis.\n");
    result.ExcuteState = false;
  }
  else
  {
    printf("more right parenthesis than left parenthesis.\n");
    result.ExcuteState = false;
  }
  return result;
}
bool needs_operator_decomposition(int p, int q)
{
  // Iterate through all tokens to find binary operators
  for (int i = p; i <= q; i++)
  {
    int type = tokens[i].type;
    // Check if it is a binary operator
    if (type == '+' || type == '-' || type == '*' || type == '/' ||
        type == TK_EQ || type == TK_NEQ)
    {
      if (i == p)
      {
        if (type == '-' || type == '*')
        {
          continue;
        }
      }
      // Check if the previous token is an operator
      if (i > p)
      {
        int prev_type = tokens[i - 1].type;
        if (prev_type == '+' || prev_type == '-' || prev_type == '*' ||
            prev_type == '/' || prev_type == TK_EQ || prev_type == TK_NEQ)
        {
          continue;
        }
      }
      return true;
    }
  }
  return false;
}
int get_operator_precedence(int type)
{
  switch (type)
  {
  case TK_EQ:
  case TK_NEQ:
    return 1; // The lowest priority
  case '+':
  case '-':
    return 2;
  case '*':
  case '/':
    return 3;
  case TK_LE:
    return 1;
  case TK_AND:
    return 0;
  default:
    return 0; // It is not a binocular operator
  }
}
int find_main_operator(int p, int q)
{
  int op_pos = -1;     // main operator default is 0, meaning is not found
  int min_prec = 4;    // found lowest, default is higher than all the operators
  int paren_level = 0; // The current nested level of parentheses
  for (int i = p; i <= q; i++)
  { // Handle parentheses, and update nested hierarchy
    if (tokens[i].type == '(')
    {
      paren_level++;
      continue;
    }
    if (tokens[i].type == ')')
    {
      paren_level--;
      continue;
    }
    if (paren_level == 0) // Search for the operator only in the outermost layer (paren level == 0)
    {
      int type = tokens[i].type;
      int prec = get_operator_precedence(type);
      if (prec > 0) // If it is a valid binocular operator
      {
        if (type == '-' || type == '*') // If it is a '-' or '*', you need to check whether it is a unary operator
        {
          if (i == p ||
              (tokens[i - 1].type == '+' || tokens[i - 1].type == '-' ||
               tokens[i - 1].type == '*' || tokens[i - 1].type == '/' ||
               tokens[i - 1].type == TK_EQ || tokens[i - 1].type == TK_NEQ ||
               tokens[i - 1].type == TK_LE ||
               tokens[i - 1].type == TK_AND ||
               tokens[i - 1].type == '('))
          {
            continue;
          }
        }
        if (prec <= min_prec) // If the current operator has a lower or equal priority, it becomes the new candidate
        {
          min_prec = prec;
          op_pos = i;
        }
      }
    }
  }
  return op_pos;
}
sword_t eval(int p, int q)
{
  ExprResult parenthese_result = check_parenthese(p, q);
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
      return 0;
      break;
    }
    return value;
  }
  else if (parenthese_result.result == true && parenthese_result.ExcuteState == true)
  {
    return eval(p + 1, q - 1);
  }
  else if (parenthese_result.result == true && parenthese_result.ExcuteState == false)
  {
    printf("Error: Parentheses mismatch\n");
    return 0;
  }
  else if (parenthese_result.result == false && parenthese_result.ExcuteState == true)
  {
    printf("The check_parenthese function return type ExprResult result==false.\n");
    return 0;
  }
  else if (parenthese_result.result == false && parenthese_result.ExcuteState == false)
  {
    printf("The check_parenthese function return type ExprResult result and ExcuteState==false\n");
    return 0;
  }
  else if (tokens[p].type == TK_MINUS)
  {
    // unary operator
    sword_t val = eval(p + 1, q);
    if (!eval_success)
      return 0;
    return -val;
  }
  else if (tokens[p].type == TK_POINTER)
  {
    // Pointer dereference - the address should be unsigned
    sword_t addr_signed = eval(p + 1, q);
    if (!eval_success)
    {
      return 0;
    }
    // Dereference Operator for Pointers
    word_t addr = (word_t)addr_signed; // Convert to an unsigned address
    return paddr_read(addr, 4);
  }
  else if (needs_operator_decomposition(p, q))
  {
    int op = find_main_operator(p, q);
    if (op == -1) // if not found main operator
    {
      printf("Error: No valid operator found in expression from token %d to %d\n", p, q);
      eval_success = false;
      return 0;
    }
    /*
    val1 = eval(p, op - 1);
    val2 = eval(op + 1, q);
    */
    sword_t left_val = eval(p, op - 1);
    if (!eval_success)
    {
      return 0;
    }
    sword_t right_val = eval(op + 1, q);
    if (!eval_success)
    {
      return 0;
    }
    switch (tokens[op].type)
    {
    case '+':
      return left_val + right_val;
      break;
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
    case TK_AND:
      return left_val && right_val;
    default:
      printf("Error: Unsupported operator type %d at position %d\n", tokens[op].type, op);
      eval_success = false;
      return 0;
      break;
    }
  }
  // Default situation: Unrecognizable expressions
  else
  {
    printf("Error: Unrecognized expression from token %d to %d\n", p, q);
    return 0;
  }
}
static void identify_unary_operators()
{
  for (int i = 0; i < nr_token; i++)
  {
    if (tokens[i].type == '-' || tokens[i].type == '*')
    {
      bool is_unary;
      if (i == 0)
      {
        // At the beginning, it must be a unary operator
        is_unary = true;
      }
      else
      {
        int prev_type = tokens[i - 1].type;
        // The operator or the left parenthesis is in front
        if (prev_type == '+' || prev_type == '-' ||
            prev_type == '*' || prev_type == '/' ||
            prev_type == TK_EQ || prev_type == TK_NEQ ||
            prev_type == TK_LE ||
            prev_type == TK_AND ||
            prev_type == '(')
        {
          is_unary = true;
        }
      }
      // Marked as a unary operator
      if (is_unary)
      {
        if (tokens[i].type == '-')
        {
          tokens[i].type = TK_MINUS; // minus
          Log("Token %d: '-' identified as unary minus (TK_MINUS)", i);
        }
        else if (tokens[i].type == '*')
        {
          tokens[i].type = TK_POINTER;
          Log("Token %d: '*' identified as pointer dereference (TK_POINTER)", i);
        }
        else
        {
          printf("I unknow identify_unary_operators() happend something.\n");
        }
      }
    }
  }
}
////