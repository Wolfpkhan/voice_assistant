#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# download-aar.sh — 下载 sherpa-onnx 预编译 AAR 到 app/libs
# AAR 已包含 kotlin-api 类(com.k2fsa.sherpa.onnx.*) + 各架构 .so
# 编译工程前必须先运行此脚本。
# ============================================================
set -euo pipefail

VERSION="1.13.4"
PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$PROJ_DIR/app/libs"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
AAR="$LIBS_DIR/sherpa-onnx-${VERSION}.aar"

mkdir -p "$LIBS_DIR"

if [ -f "$AAR" ]; then
    echo "✓ AAR 已存在: $AAR"
    ls -lh "$AAR"
    exit 0
fi

echo "⬇  下载 sherpa-onnx AAR (${VERSION}, ~49MB) ..."
echo "   $URL"
wget -c -O "$AAR.tmp" "$URL"
mv "$AAR.tmp" "$AAR"
echo "✓ 完成: $AAR"
ls -lh "$AAR"
