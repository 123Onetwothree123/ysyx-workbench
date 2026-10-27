module npc.configgen;
import std;
namespace npc::configgen {
class JsonError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};
class FlatJsonParser {
public:
    explicit FlatJsonParser(std::string_view text) : text_(text) {}
    std::map<std::string, std::string> parse()
    {
        std::map<std::string, std::string> values;
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            ++position_;
            return values;
        }
        for (;;) {
            auto key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            auto value = parseString();
            values[std::move(key)] = std::move(value);
            skipWhitespace();
            if (peek() == ',') {
                ++position_;
                skipWhitespace();
                continue;
            }
            expect('}');
            break;
        }
        return values;
    }
private:
    char peek() const
    {
        return position_ < text_.size() ? text_[position_] : '\0';
    }
    void expect(char character)
    {
        if (peek() != character) {
            throw JsonError(std::string{"JSON 解析失败：期望 '"} + character +
                            "'，实际位置 " + std::to_string(position_));
        }
        ++position_;
    }
    void skipWhitespace()
    {
        while (position_ < text_.size() &&
               std::isspace(static_cast<unsigned char>(text_[position_]))) {
            ++position_;
        }
    }
    static int hexValue(char character)
    {
        if (character >= '0' && character <= '9')
            return character - '0';
        if (character >= 'a' && character <= 'f')
            return character - 'a' + 10;
        if (character >= 'A' && character <= 'F')
            return character - 'A' + 10;
        return -1;
    }
    void appendUtf8(std::string &out, unsigned codepoint)
    {
        if (codepoint <= 0x7f) {
            out.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7ff) {
            out.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
            out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else {
            out.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
            out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        }
    }
    std::string parseString()
    {
        expect('"');
        std::string result;
        while (position_ < text_.size()) {
            const char character = text_[position_++];
            if (character == '"')
                return result;
            if (character != '\\') {
                result.push_back(character);
                continue;
            }
            if (position_ >= text_.size())
                break;
            const char escaped = text_[position_++];
            switch (escaped) {
            case '"': result.push_back('"'); break;
            case '\\': result.push_back('\\'); break;
            case '/': result.push_back('/'); break;
            case 'b': result.push_back('\b'); break;
            case 'f': result.push_back('\f'); break;
            case 'n': result.push_back('\n'); break;
            case 'r': result.push_back('\r'); break;
            case 't': result.push_back('\t'); break;
            case 'u': {
                unsigned codepoint = 0;
                for (int index = 0; index < 4; ++index) {
                    if (position_ >= text_.size())
                        throw JsonError("JSON Unicode 转义不完整");
                    const int digit = hexValue(text_[position_++]);
                    if (digit < 0)
                        throw JsonError("JSON Unicode 转义包含非法十六进制字符");
                    codepoint = (codepoint << 4) | static_cast<unsigned>(digit);
                }
                appendUtf8(result, codepoint);
                break;
            }
            default:
                throw JsonError("JSON 包含未知转义字符");
            }
        }
        throw JsonError("JSON 字符串未闭合");
    }
    std::string_view text_;
    std::size_t position_{0};
};
Config parseFlatJson(std::string_view text)
{
    return FlatJsonParser{text}.parse();
}
}
