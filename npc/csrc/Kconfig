menu "C++ 配置"

  menu "工具链"

  choice
    prompt "编译器"
    default CC_GPP
  config CC_GPP
    bool "g++"
  config CC_CLANG
    bool "clang++"
  config CC_ICPX
    bool "icpx (Intel)"
  endchoice

  config CC
    string
    default "g++" if CC_GPP
    default "clang++" if CC_CLANG
    default "icpx" if CC_ICPX
    default "g++"

  choice
    prompt "C++标准库"
    default STDLIB_LIBSTDCXX
    help
      选择C++标准库实现
      Clang支持在libstdc++与libc++之间自由切换
      GCC的-fmodules实现硬编码了libstdc++的模块映射器路径
      即使手动指向libc++头文件，GCC仍会将标准库include
      重定向到libstdc++的bits/stdc++.h模块基础设施
      因此选择libc++时此选项仅对Clang生效
      本人补充：日你妈理查德·斯托曼，浪费了我一个下午的时间
  config STDLIB_LIBSTDCXX
    bool "libstdc++（GNU）"
  config STDLIB_LIBCXX
    bool "libc++（LLVM）"
    depends on CC_CLANG || CC_ICPX
  endchoice

  config STDLIB_FLAG
    string
    default "-stdlib=libstdc++" if STDLIB_LIBSTDCXX
    default "-stdlib=libc++" if STDLIB_LIBCXX
    default "-stdlib=libstdc++"

  config LIB_HARDENING
    bool "标准库硬化 (Standard Library Hardening)"
    default n
    help
      将标准库中部分前置条件违规从UB转为运行时检查（P3471）
      libstdc++（GNU）：单级，启用后加 -D_GLIBCXX_ASSERTIONS
      libc++（LLVM）：多挡位，启用后还需选择硬化级别

  choice
    prompt "libc++硬化级别"
    default LIB_HARDENING_MODE_FAST
    depends on LIB_HARDENING && STDLIB_LIBCXX
    help
      FAST：仅检查输入区间与元素访问，开销最小，生产推荐
      EXTENSIVE：追加空指针/重叠区间/非法释放/参数域等检查
      DEBUG：全部检查，含语义要求与内部不变量，适合开发调试
  config LIB_HARDENING_MODE_FAST
    bool "FAST（生产推荐）"
  config LIB_HARDENING_MODE_EXTENSIVE
    bool "EXTENSIVE"
  config LIB_HARDENING_MODE_DEBUG
    bool "DEBUG（开发调试）"
  endchoice

  config LIB_HARDENING_MODE
    string
    default "_LIBCPP_HARDENING_MODE_FAST" if LIB_HARDENING_MODE_FAST
    default "_LIBCPP_HARDENING_MODE_EXTENSIVE" if LIB_HARDENING_MODE_EXTENSIVE
    default "_LIBCPP_HARDENING_MODE_DEBUG" if LIB_HARDENING_MODE_DEBUG
    default "_LIBCPP_HARDENING_MODE_FAST"

  config CC_ICPX_NOSTDINCXX
    bool "icpx: 跳过Intel内置C++头路径 (-nostdinc++)"
    depends on CC_ICPX && STDLIB_LIBCXX
    help
      傻逼Intel自己的include路径里夹带了一堆自己的私货，他居然包
      含了自己的 float和limits和math头文件，而且排在 libc++ 路
      径前面，然后libc++的 include的next的机制被Intel自己的头文
      件截胡，直接就崩了，卧槽，脑子有坑啊，Intel那个目录的存在纯
      粹是硬插在include搜索最前面，除了提供Fortran和他死掉的ICC的
      兼容的头文件之外屁用没有

  choice
    prompt "C库"
    default LIBC_GLIBC
    help
      C运行库实现，位于C++标准库下层，提供printf/malloc/pthread等
      musl：Arch的libstdc++和libc++均基于glibc编译，运行时依赖
      glibc特有符号（__vfprintf_chk、__isoc23_*等），musl不提供
      需要Alpine Linux等基于musl构建整套C++工具链的发行版才能用
      LLVM libc：未完成，无Arch包，不可用
      当前仅glibc可用
  config LIBC_GLIBC
    bool "glibc（GNU）"
  endchoice

  choice
    prompt "链接器"
    default LD_DEFAULT
    help
      GCC和Clang均支持切换链接器
  config LD_DEFAULT
    bool "ld（GNU）"
  config LD_GOLD
    bool "gold（GNU）"
    help
      GNU gold链接器，比ld快2-4倍
      GCC/Clang通过-fuse-ld=gold使用
  config LD_LLD
    bool "lld（LLVM）"
    help
      LLVM链接器，比ld快5-10倍
  config LD_MOLD
    bool "mold"
    help
      最快的链接器，比ld快10-20倍
  endchoice

  config LD_FLAG
    string
    default "" if LD_DEFAULT
    default "-fuse-ld=gold" if LD_GOLD
    default "-fuse-ld=lld" if LD_LLD
    default "-fuse-ld=mold" if LD_MOLD
    default ""

  endmenu

  menu "语言标准"

  choice
    prompt "用户C++语言标准"
    default USER_CXX_STD_CXX26
     help
       设置csrc目录中用户C++源码使用的语言标准
       用户源码依赖std::print等特性，最低要求C++26
   config USER_CXX_STD_CXX26
     bool "C++26"
   config USER_CXX_STD_CXX23
     bool "C++23"
  endchoice

  config USER_CXX_STD
     string
     default "c++26" if USER_CXX_STD_CXX26
     default "c++23" if USER_CXX_STD_CXX23
     default "c++26"

  choice
    prompt "Verilator生成代码语言标准"
    default VERILATOR_CXX_STD_CXX20
    help
      设置Verilator运行库、预编译头和生成模型使用的语言标准
      Verilator生成代码不依赖高版本特性，可使用较低标准
  config VERILATOR_CXX_STD_CXX17
    bool "C++17"
  config VERILATOR_CXX_STD_CXX20
    bool "C++20"
  config VERILATOR_CXX_STD_CXX23
    bool "C++23"
  config VERILATOR_CXX_STD_CXX26
    bool "C++26"
  endchoice

  config VERILATOR_CXX_STD
    string
    default "c++17" if VERILATOR_CXX_STD_CXX17
    default "c++20" if VERILATOR_CXX_STD_CXX20
    default "c++23" if VERILATOR_CXX_STD_CXX23
    default "c++26" if VERILATOR_CXX_STD_CXX26
    default "c++20"

  endmenu

  menu "编译选项"

    menu "优化"

    menu "性能优化"

    choice
      prompt "优化级别"
      default CC_O3
    config CC_O0
      bool "O0"
    config CC_O1
      bool "O1"
    config CC_O2
      bool "O2"
    config CC_O3
      bool "O3"
    config CC_OFAST
      bool "Ofast"
      help
        等同于-O3加上-ffast-math
        放宽IEEE浮点标准合规性换取速度
        可能导致浮点计算结果与标准不一致
    endchoice

    config CC_OPT
      string
      default "-O0" if CC_O0
      default "-O1" if CC_O1
      default "-O2" if CC_O2
      default "-O3" if CC_O3
      default "-Ofast" if CC_OFAST
      default "-O3"

    config CC_LTO
      bool "启用链接时优化-flto"
      default n

    choice
      prompt "PGO配置引导优化"
      default PGO_OFF
      help
        Profile-Guided Optimization，基于实际运行数据优化
    config PGO_OFF
      bool "关闭"
    config PGO_GENERATE
      bool "手动：生成profile数据"
      help
        添加-fprofile-generate，编译后运行产生profile文件
        之后切到"使用"选项重新编译
    config PGO_USE
      bool "手动：使用profile数据优化"
      help
        添加-fprofile-use，读取之前运行产生的profile文件优化
        使用前不要make clean，否则profile数据丢失
    config PGO_AUTO
      bool "自动（一步到位）"
      help
        使用make pgo自动完成全部流程：
          make pgo file=镜像文件.bin
        内部自动执行：
          1. 以-fprofile-generate编译插桩版本
          2. 运行指定的镜像文件收集热点数据
          3. 以-fprofile-use重新编译，应用profile优化
          4. 运行最终优化后的程序
        注意：必须通过file=指定要运行的镜像文件
    endchoice

    choice
      prompt "架构优化-march"
      default MARCH_OFF
    config MARCH_OFF
      bool "不指定"
    config MARCH_NATIVE
      bool "native（本机CPU）"
    config MARCH_CUSTOM
      bool "手动指定"
    endchoice

    config MARCH_CUSTOM_VALUE
      string "架构名称"
      default "x86-64-v3"
      depends on MARCH_CUSTOM
      help
        输入GCC/Clang支持的架构名，例如：
        alderlake, raptorlake, znver4, skylake, x86-64-v3

    config CC_IPA_PTA
      bool "跨过程指针分析-fipa-pta"
      default n
      depends on CC_GPP
      help
        允许编译器跨函数分析指针别名，发现更多优化机会
        会显著增加编译时间（仅GCC支持）

    config CC_PREFETCH
      bool "软件预取大数组-fprefetch-loop-arrays"
      default n
      depends on CC_GPP
      help
        在循环中插入prefetch指令提前加载数据到缓存
        对遍历大数组的场景有效，对小数据量可能反而变慢（仅GCC支持）

    config CC_PIPE
      bool "使用管道加速编译-pipe"
      default n
      help
        编译阶段间用管道代替临时文件，减少磁盘IO

    config CC_SPLIT_DWARF
      bool "拆分调试信息加速链接-gsplit-dwarf"
      default n
      help
        将调试信息拆分到.dwo文件，减少链接器处理量
        需配合-g使用

    endmenu

    menu "大小优化"

    config CC_GC_SECTIONS
      bool "删除未使用代码和数据-ffunction-sections -fdata-sections"
      default n
      help
        将每个函数和数据放入独立section
        链接时通过--gc-sections丢弃未引用的section

    config CC_STRIP
      bool "剥离符号表-s"
      default n
      help
        链接时删除所有符号信息，减小二进制体积
        启用后无法用gdb调试

    config CC_VISIBILITY_HIDDEN
      bool "默认隐藏符号-fvisibility=hidden"
      default n
      help
        所有符号默认不导出，减少动态链接开销
        对可执行文件无副作用

    endmenu

    endmenu

    menu "警告"

    config CC_WALL
      bool "启用额外警告-Wall -Wextra"
      default n

    config CC_WERROR
      bool "将警告视为错误-Werror"
      default n

    config CC_PEDANTIC
      bool "严格ISO C++合规警告-Wpedantic"
      default n

    config CC_WCONVERSION
      bool "隐式类型转换警告-Wconversion"
      default n

    config CC_COLOR
      bool "强制彩色编译输出-fdiagnostics-color=always"
      default n

    endmenu

    menu "调试与分析"

    config CC_DEBUG
      bool "生成调试信息-g"
      default n

    config CC_NO_OMIT_FP
      bool "保留帧指针-fno-omit-frame-pointer"
      default n

    config CC_ASAN
      bool "启用ASan地址错误检测-fsanitize=address"
      default n

    config CC_UBSAN
      bool "启用UBSan未定义行为检测-fsanitize=undefined"
      default n

    config CC_TSAN
      bool "启用TSan线程竞争检测-fsanitize=thread"
      default n
      help
        检测数据竞争，不能与ASan同时使用

    config CC_COVERAGE
      bool "启用代码覆盖率--coverage"
      default n
      help
        编译后运行程序会生成.gcda/.gcno文件
        用gcov/lcov/genhtml生成覆盖率报告

    config CC_GPROF
      bool "启用gprof性能分析-pg"
      default n
      help
        运行后生成gmon.out，用gprof分析热点函数

    config CC_TIME_REPORT
      bool "显示编译各阶段耗时-ftime-report"
      default n

    config BUILD_TIMESTAMP
      bool "嵌入构建时间戳"
      default n
      help
        将编译时间嵌入二进制，可在运行时打印版本信息

    endmenu

  endmenu

endmenu
