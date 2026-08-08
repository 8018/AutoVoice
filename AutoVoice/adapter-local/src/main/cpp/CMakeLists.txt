# rnnoise JNI 构建（NDK 默认 toolchain，无外部依赖）
cmake_minimum_required(VERSION 3.22.1)
# C CXX 都要启用：C 编 rnnoise 源码，CXX 编 JNI 桥（.cpp 在 C-only 项目里会被 CMake 静默排除）
project(rnnoise_jni C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 源文件与 xiph/rnnoise v0.2 的 RNNOISE_SOURCES 对齐（去掉 x86 RTCD，arm 走通用 C 实现）
add_library(rnnoise_jni SHARED
    com_autovoice_adapterlocal_ecnr_RnnoiseNative.cpp
    denoise.c
    rnn.c
    pitch.c
    kiss_fft.c
    celt_lpc.c
    nnet.c
    nnet_default.c
    parse_lpcnet_weights.c
    rnnoise_data.c
    rnnoise_tables.c
)

target_include_directories(rnnoise_jni PRIVATE .)
# DISABLE_DEBUG_FLOAT：剔除 rnnoise_data.c 里 int8 层的 float 调试副本（缩小 so，
# conv1 与 int8+scale 路径不受影响）；O3 对 DSP/RNN 数值运算重要
target_compile_options(rnnoise_jni PRIVATE -O3 -DDISABLE_DEBUG_FLOAT)
target_link_libraries(rnnoise_jni m)
