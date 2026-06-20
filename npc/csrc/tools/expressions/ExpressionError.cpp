module npc.expressions.ExpressionError;
ExpressionError::ExpressionError(std::string InputMessage)
{
    message = std::move(InputMessage);
}
const char *ExpressionError::what() const noexcept
{
    return message.c_str();
}
