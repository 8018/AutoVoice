#!/usr/bin/env bash
# 在阿里云服务器（x86-64 Linux）编译 JNI 桥 autovoice_offline_esr.so。
# 产物路径：<SDK_DIR>/libautovoice_offline_esr.so（与 application-demo-full.yml 的
# offline.sdk.lib-path 一致）。
#
# 用法：
#   ./build.sh [SDK_DIR]
#     SDK_DIR 默认 /opt/autovoice/iflytek-offline（须包含 include/ 与 libs/）
#     （离线 SDK 头文件与 libs 已随部署上传，见 deploy/README.md）
#
# 前置：服务器有 g++（dnf install gcc-c++）与 JDK（java-21-openjdk-headless）。
set -euo pipefail

SDK_DIR="${1:-/opt/autovoice/iflytek-offline}"
OUT="$SDK_DIR/libautovoice_offline_esr.so"

# 定位 JDK 头文件：JAVA_HOME → javac 所在 → 常见安装路径
find_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME/include" ]]; then
        echo "$JAVA_HOME"
        return 0
    fi
    local javac
    javac="$(command -v javac 2>/dev/null || true)"
    if [[ -n "$javac" ]]; then
        local resolved
        resolved="$(readlink -f "$javac")"
        echo "${resolved%/bin/javac}"
        return 0
    fi
    for j in /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk*; do
        if [[ -d "$j/include" ]]; then
            echo "$j"
            return 0
        fi
    done
    return 1
}

JAVA_HOME="$(find_java_home)"
if [[ -z "$JAVA_HOME" ]]; then
    echo "ERROR: JDK include 目录未找到（需 java-21-openjdk-headless）。" >&2
    exit 1
fi

if [[ ! -d "$SDK_DIR/include" || ! -d "$SDK_DIR/libs" ]]; then
    echo "ERROR: $SDK_DIR 缺少 include/ 或 libs/（先按 deploy/README.md 上传 SDK）。" >&2
    exit 1
fi

g++ -std=c++11 -O2 -shared -fPIC \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -I"$SDK_DIR/include" \
    autovoice_offline_esr.cpp \
    -L"$SDK_DIR/libs" -laikit -lpthread -ldl \
    -Wl,-rpath,"$SDK_DIR/libs" \
    -o "$OUT"

echo "OK: $OUT"
echo "    （rpath 已指向 $SDK_DIR/libs，运行时无需 LD_LIBRARY_PATH；systemd unit 的）"
echo "    Environment=LD_LIBRARY_PATH=... 保留作双保险。"
