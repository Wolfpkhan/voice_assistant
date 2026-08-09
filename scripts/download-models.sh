#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# download-models.sh — 下载 VAD / ASR / TTS 模型到 app/src/main/assets/models
#
# 模型清单（体积合计 ~120MB，解压后 ~300MB）：
#   VAD : silero_vad.onnx                                  (~2MB)
#   ASR : sherpa-onnx-paraformer-zh-2023-09-14 (含 int8)    (~234MB 压缩)
#   TTS : matcha-icefall-zh-baker + vocos-22khz-univ 声码器  (~76MB)
#
# 注意：assets 打包进 APK 会增大安装包。若想减小 APK，可改用运行时
# 下载到 filesDir（见工程内 ModelManager.kt 的实现）。
# 本脚本默认放 assets，便于离线首次运行。
# ============================================================
set -euo pipefail

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

echo "======== 3/4 TTS: matcha-baker + vocos 声码器 ========"
if [ ! -d "matcha-icefall-zh-baker" ]; then
    wget -c -O mk.tar.bz2 \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/matcha-icefall-zh-baker.tar.bz2"
    tar xjf mk.tar.bz2
    rm -f mk.tar.bz2
fi
# vocos 声码器放在 models 根目录（与模型根同级，按 sherpa 约定）
dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-22khz-univ.onnx" \
   "$MODELS_DIR/vocos-22khz-univ.onnx"
echo "✓ TTS: matcha-icefall-zh-baker + vocos-22khz-univ.onnx"

echo ""
echo "========================================"
echo "✅ 全部模型就绪: $MODELS_DIR"
echo "========================================"
du -sh "$MODELS_DIR"/* 2>/dev/null | sort -h
echo ""
echo "提示: 这些模型会被打包进 APK 的 assets。"
echo "      若想瘦身 APK，可删除此目录，改用 App 内运行时下载(见 ModelManager)。"
