# Sherpa 语音助手 (Android)

基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的端到端安卓语音助手，
目标骁龙（arm64）手机。

```
说话 → [VAD 端点检测] → [ASR 语音识别] → [云端 LLM 流式] → [TTS 分句播报] → 继续聆听
       └────── 以下三项全部本地运行 (sherpa-onnx) ──────┘   ↑ 仅此项走云端
```

## 模型选型

| 环节 | 模型 | 体积 | 说明 |
|------|------|------|------|
| VAD | silero-vad v4 | ~2MB | 端点检测，CPU 极轻量 |
| ASR | paraformer-zh-2023-09-14 (int8) | ~234MB | 中文识别，骁龙 4 线程约 100~200ms/句 |
| TTS | matcha-icefall-zh-baker + vocos | ~76MB | 中文女声(Baker)，流式合成首响快 |
| LLM | 任选 (DeepSeek/通义/智谱/OpenAI...) | 0 | OpenAI 兼容 SSE 流式 |

> ⚠️ sherpa-onnx 官方库**没有 VITS-Baker**，Baker 数据集的中文女声对应的是
> **Matcha-TTS** (`matcha-icefall-zh-baker`)。若坚持 VITS 架构，可改用
> `vits-melo-tts-zh_en`（见 `ModelPaths.kt` 注释）。

## 快速开始 (Termux 编译)

```bash
# 1. 下载 sherpa-onnx AAR（含 kotlin-api 类 + 各架构 .so，~49MB）
bash scripts/download-aar.sh

# 2. 下载端侧模型到 assets（VAD+ASR+TTS，合计 ~300MB）
bash scripts/download-models.sh

# 3. 编译
./gradlew assembleDebug

# 4. 安装到本机
./install-apk.sh
```

首次 `assembleDebug` 需下载 Gradle/Kotlin 依赖，约 3~5 分钟。

## 使用

1. 打开 App → 「设置」→ 填写云端 LLM 的 **Base URL / API Key / 模型**
   - DeepSeek: `https://api.deepseek.com/v1` + `deepseek-chat`
   - 通义(DashScope 兼容): `https://dashscope.aliyuncs.com/compatible-mode/v1` + `qwen-plus`
   - 智谱: `https://open.bigmodel.cn/api/paas/v4` + `glm-4-flash`
2. 授予录音权限 → 「开始对话」→ 说话即可，答完自动继续聆听。
3. 状态栏颜色：🟢聆听 🟠识别 🔵思考 🟣播报。

## 工程结构

```
app/src/main/java/com/sherva/voiceassistant/
├── ModelPaths.kt          # 模型 assets 路径集中配置（切换模型改这里）
├── MainActivity.kt        # 主界面 + 权限 + UI 接线
├── SettingsActivity.kt    # LLM/TTS 设置页
├── audio/AudioRecorder.kt # 16kHz 采集 → float PCM
├── vad/VadEngine.kt       # silero-vad 封装，产出完整语音段
├── asr/AsrEngine.kt       # paraformer int8 识别
├── tts/TtsEngine.kt       # matcha-baker 流式合成播放（可中断）
├── llm/LlmClient.kt       # OpenAI 兼容 SSE 流式客户端
└── pipeline/
    └── VoiceAssistant.kt  # 状态机编排：VAD→ASR→LLM→TTS 全链路
```

## 性能优化方向

- **QNN/HTP 加速（已支持，可切换）**：设置页「ASR 加速」选 QNN，走骁龙 NPU。
  官方无中文流式 QNN 模型，故 QNN 模式为：离线 paraformer QNN 5 秒窗 +
  silero VAD + 滑窗模拟流式。启用步骤：
  ```bash
  bash scripts/download-models.sh --qnn   # QNN 模型包(~70MB)到 assets
  bash scripts/download-qnn-libs.sh       # libQnnHtp.so 等到 jniLibs
  # 重新编译安装；设置 → 高级 → ASR 加速 → QNN
  ```
  运行时 QNN 初始化失败（缺模型/缺so/芯片不支持）会自动回退 CPU，
  日志 tag `ASR` 可见实际 provider。
- **APK 瘦身**：模型不打包进 assets，改运行时下载到 `filesDir`（见
  `ModelPaths.ensureExtracted`），APK 可从 ~350MB 降到 ~15MB。
- **低延迟播报**：当前已实现 LLM 流式 token 边收边按句切分入 TTS 队列；
  进一步可降低 VAD `minSilenceDuration` 到 0.3s 让应答更紧凑。

## 依赖

- sherpa-onnx 1.13.4 (AAR)
- Kotlin + Coroutines
- OkHttp (SSE)
- AndroidX / Material Components
