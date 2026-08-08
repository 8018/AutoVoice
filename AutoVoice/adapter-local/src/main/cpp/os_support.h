/*
 * 最小 os_support.h：xiph/rnnoise 的 vec.h/vec_neon.h 在 NEON 与非 NEON 两条
 * 路径里都会 #include "os_support.h"（opus/celt 头，rnnoise 仓库本身不携带）。
 * rnnoise 源码实际用到的符号只有 OPUS_CLEAR（celt_assert/celt_fatal 等由
 * 仓库自带 arch.h 提供），这里按 opus celt/os_support.h 的语义补齐。
 */
#ifndef OS_SUPPORT_H
#define OS_SUPPORT_H

#include <string.h>

#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, l) memset((dst), 0, (l) * sizeof(*(dst)))
#endif

#endif /* OS_SUPPORT_H */
