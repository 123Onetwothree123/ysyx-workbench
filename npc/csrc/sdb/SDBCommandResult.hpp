#ifndef SDB_COMMAND_RESULT_HPP
#define SDB_COMMAND_RESULT_HPP
enum class SDBCommandResult
{
    Continue, // 他妈的continue是C++关键字不能用，英语词汇又匮乏，找不到合适的单词了
    Quit,     // 为了和Continue统一风格，首字母大写
};
#endif