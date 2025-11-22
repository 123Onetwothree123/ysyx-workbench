#include <iostream>
#include <fstream>
#include <vector>
#include <iomanip>
#include <cstdint> // 必须包含这个头文件来支持 uint16_t 和 uint32_t

// 强制编译器不添加字节对齐填充，确保读取的数据直接对应 BMP 文件格式
#pragma pack(push, 1)
struct BMPHeader {
    uint16_t bfType;      // 必须是 0x4D42 ('BM')
    uint32_t bfSize;
    uint16_t bfReserved1;
    uint16_t bfReserved2;
    uint32_t bfOffBits;   // 像素数据开始的偏移量
};

struct BMPInfo {
    uint32_t biSize;
    int32_t  biWidth;
    int32_t  biHeight;
    uint16_t biPlanes;
    uint16_t biBitCount;  // 每像素位数
    uint32_t biCompression;
    uint32_t biSizeImage;
    int32_t  biXPelsPerMeter;
    int32_t  biYPelsPerMeter;
    uint32_t biClrUsed;
    uint32_t biClrImportant;
};
#pragma pack(pop)

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cout << "用法: " << argv[0] << " <input.bmp> <output.hex>" << std::endl;
        return -1;
    }

    std::ifstream bmpFile(argv[1], std::ios::binary);
    if (!bmpFile) {
        std::cerr << "错误: 无法打开 BMP 文件: " << argv[1] << std::endl;
        return -1;
    }

    // 1. 读取头部
    BMPHeader header;
    BMPInfo info;
    bmpFile.read(reinterpret_cast<char*>(&header), sizeof(header));
    bmpFile.read(reinterpret_cast<char*>(&info), sizeof(info));

    // 校验格式
    if (header.bfType != 0x4D42) {
        std::cerr << "错误: 不是有效的 BMP 文件！" << std::endl;
        return -1;
    }
    if (info.biBitCount != 24) {
        std::cerr << "错误: 仅支持 24 位 BMP 格式！当前图片为 " << info.biBitCount << " 位。" << std::endl;
        std::cerr << "请使用画图(Paint)将图片另存为 '24位位图(24-bit Bitmap)'。" << std::endl;
        return -1;
    }

    int width = info.biWidth;
    int height = (info.biHeight < 0) ? -info.biHeight : info.biHeight; // 处理可能的负高度情况
    
    // BMP 的行字节数必须是 4 的倍数 (Padding)
    int rowSize = ((width * 3 + 3) / 4) * 4;
    
    std::vector<unsigned char> rowData(rowSize);
    std::vector<uint32_t> pixels(256 * 256, 0xFFFFFF); // 预存 256x256，默认白色背景

    // 2. 读取像素数据
    for (int y = 0; y < height; ++y) {
        // 如果图片高度超过 256，跳过
        if (y >= 256) break;

        // 计算当前行在文件中的偏移量。注意：标准 BMP 像素是从下往上存的
        int fileY = (info.biHeight > 0) ? (height - 1 - y) : y;
        bmpFile.seekg(header.bfOffBits + fileY * rowSize);
        bmpFile.read(reinterpret_cast<char*>(rowData.data()), rowSize);
        
        for (int x = 0; x < width; ++x) {
            if (x < 256) {
                // BMP 内部颜色存储顺序是 B, G, R
                unsigned char b = rowData[x * 3];
                unsigned char g = rowData[x * 3 + 1];
                unsigned char r = rowData[x * 3 + 2];
                // 拼接为 0xRRGGBB
                pixels[y * 256 + x] = (static_cast<uint32_t>(r) << 16) | 
                                      (static_cast<uint32_t>(g) << 8)  | 
                                       static_cast<uint32_t>(b);
            }
        }
    }

    // 3. 写入 HEX 文件
    std::ofstream hexFile(argv[2]);
    if (!hexFile) {
        std::cerr << "错误: 无法创建输出文件: " << argv[2] << std::endl;
        return -1;
    }

    for (int i = 0; i < 256 * 256; ++i) {
        hexFile << std::hex << std::uppercase << std::setfill('0') 
                << std::setw(6) << pixels[i] << "\n";
    }

    std::cout << "成功生成 " << argv[2] << " (" << width << "x" << height << " -> 256x256)" << std::endl;
    return 0;
}