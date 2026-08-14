#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# download-models.sh — 下载 VAD / ASR / TTS 模型到 app/src/main/assets/models
#
# 模型清单（体积合计 ~450MB，解压后 ~600MB）：
#   VAD : silero_vad.onnx + GTCRN 增强                       (~3MB)
#   ASR : sherpa-onnx-streaming-zipformer-bilingual int8     (~234MB 压缩)
#   TTS : kokoro-int8-multi-lang-v1_1（中英双语+103 音色）   (~175MB)
#
# 可选：--qnn  额外下载 QNN(NPU) 版 paraformer 5 秒窗        (~70MB)
#       （配合 scripts/download-qnn-libs.sh 与设置页「ASR 加速」）
#
# 注意：assets 打包进 APK 会增大安装包。若想减小 APK，可改用运行时
# 下载到 filesDir（见工程内 ModelManager.kt 的实现）。
# 本脚本默认放 assets，便于离线首次运行。
# ============================================================
set -euo pipefail

WITH_QNN=0
[ "${1:-}" = "--qnn" ] && WITH_QNN=1

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="$PROJ_DIR/app/src/main/assets/models"
mkdir -p "$MODELS_DIR"

dl() {  # dl <url> <dest>
    local url="$1" dest="$2"
    if [ -f "$dest" ]; then echo "✓ 已存在: $(basename "$dest")"; return; fi
    echo "⬇  $(basename "$dest")"
    wget -c -O "$dest.tmp" "$url" && mv "$dest.tmp" "$dest"
}

echo "======== 1/4 VAD: silero_vad.onnx + GTCRN 语音增强 ========"
dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
   "$MODELS_DIR/silero_vad.onnx"
dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx" \
   "$MODELS_DIR/gtcrn_simple.onnx"

echo "======== 2/4 ASR: streaming-zipformer 流式 (int8) ========"
cd "$MODELS_DIR"
if [ ! -d "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20" ]; then
    wget -c -O strm.tar.bz2 \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2"
    tar xjf strm.tar.bz2
    rm -f strm.tar.bz2
fi
echo "✓ ASR(流式): $(ls -d sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20)"

echo "======== 3/4 TTS: kokoro-int8-multi-lang-v1_1（中英双语+103 音色）========"
if [ ! -d "kokoro-int8-multi-lang-v1_1" ]; then
    wget -c -O kokoro-int8.tar.bz2 \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
    tar xjf kokoro-int8.tar.bz2
    rm -f kokoro-int8.tar.bz2
fi
echo "✓ TTS: kokoro-int8-multi-lang-v1_1（中英双语+103音色，int8 量化）"

echo "======== 4/4 [可选] ASR QNN: paraformer-zh 5秒窗 (NPU/HTP) ========"
if [ "$WITH_QNN" = "1" ]; then
    QNN_DIR="sherpa-onnx-qnn-5-seconds-paraformer-zh-2025-10-07-int8-android-aarch64"
    if [ ! -d "$QNN_DIR" ]; then
        wget -c -O qnn.tar.bz2 \
          "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn/${QNN_DIR}.tar.bz2"
        tar xjf qnn.tar.bz2
        rm -f qnn.tar.bz2
        # 只保留模型三件套 + tokens（去掉示例音频等）
        find "$QNN_DIR" -maxdepth 1 -type f \
          ! -name 'libencoder.so' ! -name 'libpredictor.so' ! -name 'libdecoder.so' ! -name 'tokens.txt' \
          -delete 2>/dev/null || true
    fi
    echo "✓ ASR(QNN): $QNN_DIR"
    echo "  提醒：还需运行 scripts/download-qnn-libs.sh 提供 libQnnHtp.so"
else
    echo "⏭  跳过 QNN 模型（加 --qnn 开启）"
fi

echo ""
echo "========================================"
echo "✅ 全部模型就绪: $MODELS_DIR"
echo "========================================"
du -sh "$MODELS_DIR"/* 2>/dev/null | sort -h
echo ""
echo "提示: 这些模型会被打包进 APK 的 assets。"
echo "      若想瘦身 APK，可删除此目录，改用 App 内运行时下载(见 ModelManager)。"
