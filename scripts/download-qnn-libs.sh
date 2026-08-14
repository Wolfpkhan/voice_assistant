#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# download-qnn-libs.sh — 下载高通 QNN 运行库到 app/src/main/jniLibs/arm64-v8a
#
# QNN(NPU/HTP) 加速必需的三个库：
#   libQnnHtp.so          — HTP 后端主库（运行时按设备加载对应 Skel）
#   libQnnSystem.so       — QNN 系统库
#   libQnnHtpV*Skel.so    — 各代 HTP 架构的 Skel（V68=888, V69, V73=8Gen1/2, V75=8Gen3, V79/V81…）
#
# 高通 QNN SDK 官方需在 https://qpm.qualcomm.com 登录下载（QNN SDK 2.2x），
# 解压后位于 hexagon-v73/unsigned/lib/aarch64-android/libQnnHtpV73Skel.so 等。
# 若下方镜像失效，请手动拷贝以下文件到 jniLibs/arm64-v8a 后重新编译：
#   libQnnHtp.so / libQnnSystem.so / libQnnHtpV68Skel.so / libQnnHtpV69Skel.so
#   libQnnHtpV73Skel.so / libQnnHtpV75Skel.so / libQnnHtpV79Skel.so ...
#   （至少包含你手机芯片对应代次的 Skel，多放几代不影响）
#
# 注意：libQnnHtp.so 版本需与 sherpa-onnx AAR 内 libonnxruntime.so
#       编译时链接的 QNN SDK 版本兼容（1.13.x 对应 QNN 2.3x）。
# ============================================================
set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$PROJ_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

# 公开镜像（sherpa-onnx 相关项目托管的 QNN 2.31 运行库，按需替换）
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/qnn-engine"
FILES=(
    "libQnnHtp.so"
    "libQnnSystem.so"
    "libQnnHtpV68Skel.so"
    "libQnnHtpV69Skel.so"
    "libQnnHtpV73Skel.so"
    "libQnnHtpV75Skel.so"
    "libQnnHtpV79Skel.so"
    "libQnnHtpV81Skel.so"
)

ok=0
fail=0
for f in "${FILES[@]}"; do
    if [ -f "$DEST/$f" ]; then
        echo "✓ 已存在: $f"
        ok=$((ok+1))
        continue
    fi
    echo "⬇  $f"
    if wget -q -c -O "$DEST/$f.tmp" "$BASE/$f"; then
        mv "$DEST/$f.tmp" "$DEST/$f"
        ok=$((ok+1))
    else
        rm -f "$DEST/$f.tmp"
        echo "  ✗ 下载失败: $BASE/$f"
        fail=$((fail+1))
    fi
done

echo ""
echo "========================================"
if [ "$ok" -gt 0 ] && [ "$fail" -eq 0 ]; then
    echo "✅ QNN 运行库就绪: $DEST"
    ls -lh "$DEST" | grep -i qnn || true
    echo "下一步: bash scripts/download-models.sh --qnn && 设置页选「QNN」"
else
    echo "⚠  $ok 个成功 / $fail 个失败。"
    echo "镜像不可用时，请从高通 QNN SDK 手动拷贝（见脚本头部说明）："
    echo "  1. https://qpm.qualcomm.com 下载 Qualcomm AI Engine Direct SDK"
    echo "  2. 解压后把 lib/aarch64-android/ 下的 libQnn*.so 拷入 $DEST"
fi
echo "========================================"
