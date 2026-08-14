#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# download-qnn-libs.sh — 下载高通 QNN 运行库到 app/src/main/jniLibs/arm64-v8a
#
# ✅ 来源：sherpa-onnx 官方 asr-models-qnn release 托管的 QNN 2.40 运行库
#    https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-libs-2.40.0.251030.tar.bz2
#    含 libQnnHtp.so / libQnnSystem.so / libQnnHtpPrepare.so 及 V68~V81 各代 Skel
#
# ⚠️ 重要：仅放入这些库还不够！标准 sherpa-onnx AAR 未编译 QNN backend
#    （libsherpa-onnx-jni.so 无 csrc/qnn/* 代码），需用 scripts/build-qnn-jni.sh
#    重编含 QNN 的 libsherpa-onnx-jni.so 并替换 AAR 内同名文件。
#
# 若上述镜像失效，可从高通官方获取：
#   1. https://qpm.qualcomm.com 下载 Qualcomm AI Engine Direct (QAIRT) SDK
#   2. 解压后把 lib/aarch64-android/ 下的 libQnn*.so 拷入 jniLibs/arm64-v8a
#   3. 头文件用 asr-models-qnn/qnn-include-2.40.0.251030.tar.bz2（重编时需要）
# ============================================================
set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$PROJ_DIR/app/src/main/jniLibs/arm64-v8a"
WORK="$PROJ_DIR/.qnn-libs-tmp"
mkdir -p "$DEST"

VER="2.40.0.251030"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/qnn-libs-${VER}.tar.bz2"

if [ -f "$DEST/libQnnHtp.so" ] && [ -f "$DEST/libQnnSystem.so" ]; then
    echo "✓ QNN 运行库已存在: $DEST"
    ls -lh "$DEST" | grep -i qnn || true
    exit 0
fi

echo "⬇  下载 QNN ${VER} 运行库 (~136MB)..."
mkdir -p "$WORK"
wget -c -O "$WORK/qnn-libs.tar.bz2" "$URL"
tar xjf "$WORK/qnn-libs.tar.bz2" -C "$WORK"

# 只拷贝运行必需：HTP 后端 + System + Prepare（首次生成 context binary 用）+ 全部 Skel
# （Skel 每个约 10MB，按芯片代次运行时自动选择，多放不影响正确性；要省体积可只留对应代次）
SRC="$WORK/qnn-libs-${VER}"
for f in libQnnHtp.so libQnnSystem.so libQnnHtpPrepare.so; do
    cp -v "$SRC/$f" "$DEST/"
done
for f in "$SRC"/libQnnHtpV*Skel.so; do
    cp -v "$f" "$DEST/"
done

rm -rf "$WORK"

echo ""
echo "========================================"
echo "✅ QNN 运行库就绪: $DEST"
ls -lh "$DEST" | grep -i qnn
echo ""
echo "下一步："
echo "  1. bash scripts/build-qnn-jni.sh   # 重编含 QNN 的 libsherpa-onnx-jni.so（Termux 原生编译）"
echo "  2. bash scripts/download-models.sh --qnn && ./gradlew assembleDebug"
echo "========================================"
