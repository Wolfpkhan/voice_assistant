# AGENTS.md — SherpaVoiceAssistant 项目进度与上下文

> 最后更新：2026-08-14
> 用途：为 coding agent 提供项目背景、当前进度与后续方向，避免重复探索。

## 项目概况

基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的安卓端到端语音助手，目标骁龙（arm64）手机。

```
说话 → [VAD 端点检测] → [ASR 语音识别] → [云端 LLM 流式] → [TTS 分句播报] → 继续聆听
       └── 三项全部本地运行 (sherpa-onnx) ──┘   ↑ 仅此项走云端
```

| 环节 | 模型 | 体积 | 说明 |
|------|------|------|------|
| VAD | silero-vad v4 | ~2MB | 端点检测 |
| ASR | paraformer-zh-2023-09-14 (int8) | ~234MB | 中文识别 |
| TTS | matcha-icefall-zh-baker + vocos | ~76MB | 中文女声，流式合成 |
| LLM | OpenAI 兼容 SSE | 0 | DeepSeek/通义/智谱等 |

## 技术栈与规模

- **语言/构建**：Kotlin + Gradle，AndroidX / Material / OkHttp(SSE) / Room / Markwon
- **核心依赖**：sherpa-onnx 1.13.4 AAR（`app/libs/`，~47MB）
- **源码规模**：39 个 Kotlin 文件，约 5122 行（`app/src/main/java/com/sherva/voiceassistant/`）
- **开发周期**：2026-08-09 ~ 08-14（连续 6 天），143 次提交
- **产物**：`app/build/outputs/apk/debug/app-debug.apk`（~346MB，模型打包进 assets）

## 工程结构

```
app/src/main/java/com/sherva/voiceassistant/
├── ModelPaths.kt              # 模型路径集中配置（切换模型改这里）
├── MainActivity.kt            # 主界面 + 权限 + UI 接线
├── SettingsActivity.kt        # LLM/TTS 设置页
├── HistoryActivity.kt         # 历史记录页
├── AppLog.kt / StoragePermission.kt / App.kt
├── audio/
│   ├── AudioRecorder.kt       # 16kHz 采集 → float PCM
│   ├── AecManager.kt / AecProbe.kt    # 回声消除（TTS 播报时不误识别）
│   ├── SpeechEnhancer.kt      # 语音增强
│   └── SoundEffects.kt        # 提示音
├── vad/VadEngine.kt           # silero-vad 封装
├── asr/
│   ├── AsrEngine.kt           # paraformer 离线识别（备用；主链路用流式）
│   ├── StreamingAsrEngine.kt  # 流式识别（CPU）
│   └── KeywordSpotterEngine.kt # 唤醒词 KWS
├── tts/
│   ├── TtsEngine.kt           # matcha-baker 流式合成（可中断）
│   ├── SystemTtsEngine.kt / TtsProvider.kt
├── llm/LlmClient.kt           # OpenAI 兼容 SSE 流式客户端
├── pipeline/VoiceAssistant.kt # 状态机编排：VAD→ASR→LLM→TTS 全链路
├── service/
│   ├── VoiceAssistantService.kt  # 前台服务
│   └── FloatingBallManager.kt     # 悬浮球
├── data/                      # Room: ChatStore/MessageDao/MessageEntity/AppDatabase
│   └── BackupManager.kt / BackupSerializer.kt  # 导入导出
└── ui/
    ├── ChatAdapter.kt / ChatMessage.kt  # RecyclerView 气泡
    └── MarkdownRenderer.kt              # Markwon 封装
```

## 当前进度（截至 08-14）

### ✅ 已完成

**核心语音链路**
- [x] VAD 端点检测 → ASR → LLM 流式 → TTS 分句播报全链路跑通
- [x] 唤醒词 KWS（含后台暂停回前台自动恢复，08-14 修复）
- [x] 流式 ASR、AEC 回声消除（TTS 播报期间不误识别）
- [x] LLM 流式 token 边收边按句切分入 TTS 队列（低延迟播报）

**聊天 UI**
- [x] 聊天气泡 + Markdown 渲染（流式期间跳过 Markwon，complete 后再渲染，防闪烁）
- [x] reasoning 思考过程实时流式显示（可折叠/展开，结束后自动折叠）——08-14 连环修复约 15 个 bug 后收尾
- [x] 发送后自动滚到底部 + 收起键盘、夜间模式、新对话内联提示
- [x] 正文流式增量 append（不再全量 setText）

**数据与后台**
- [x] Room 持久化 + 历史搜索 + JSON 导入/导出备份
- [x] 前台服务 + 悬浮球快捷入口
- [x] 息屏保活（WakeLock）+ 切后台自动暂停 + 屏幕常亮设置
- [x] 语音/文字双模式互斥切换（不同系统提示词）
- [x] 音效反馈（发送/重新聆听等）

### 📝 开发时间线（按日期）

| 日期 | 主题 |
|------|------|
| 08-09 | 项目初始化，基础链路跑通 |
| 08-10~12 | KWS 唤醒词、AEC、Room 持久化、模式切换、音效 |
| 08-13 | Markdown 渲染、UI 美化、文字模式系列修复 |
| 08-14 | reasoning 思考流式显示攻坚（~15 commits）+ KWS 后台恢复修复 |
| 08-15 | QNN 移除、AudioFocus 试错回退、API Key 去硬编码、文本模式不暂停音乐、四项增强（夜间模式/LLM截断/错误UI/设置备份） |

### 🔜 待办 / 优化方向

- [ ] **APK 瘦身**（优先）：模型不打包 assets，改运行时下载到 `filesDir`，~350MB → ~15MB
- [ ] **低延迟**：VAD `minSilenceDuration` 降至 0.3s
- [ ] 可选：VITS-Baker 不存在，若要 VITS 架构用 `vits-melo-tts-zh_en`（见 `ModelPaths.kt` 注释）

### ❌ 已否决方向（避免重复踩坑）

- **QNN/NPU 加速（2026-08-15 真机验证否决）**：vivo V2303A（SM8550/Android 16）
      cDSP 拒载任何第三方 HTP Skel（deviceCreate 14001，unsigned 官方库与 odm
      签名版均不行），高通量产安全模型所致，需厂商白名单/系统签名/root。
      完整实现见 git 历史（47567c2），换解锁机可参考重试。
      附带收获：重编过抗崩溃版 libsherpa-onnx-jni.so（SHERPA_ONNX_EXIT→抛异常），
      当前 AAR 即此版本，native 报错不再杀进程。

- **原生 AudioFocus 代替 TermuxRemoteFrontend（2026-08-15 回退）**：尝试
      `requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)` 让 QQ音乐
      在 vivo V2303A 自动暂停，**GRANTED 但对方不响应**。结论：vivo
      中间层拦截 / QQ音乐 vivo 版仅 duck 不暂停，标准焦点机制对该设备不可靠。
      已回退到原 `/media_pause_all` HTTP 方案。替代方案需走通知使用权 +
      MediaController（需用户授权特殊权限，暂不实施）。完整试错记录见 git stash 历史。

## 开发备忘

- **编译**（Termux 环境）：
  ```bash
  bash scripts/download-aar.sh      # 首次：下载 sherpa-onnx AAR
  bash scripts/download-models.sh  # 首次：下载模型到 assets
  ./gradlew assembleDebug
  ./install-apk.sh                 # 安装到本机
  ```
- **测试**：当前无自动化测试（0 个），验证依赖手动真机测试
- **unused_models/**：存放历史实验模型（paraformer 副本、streaming-fp32、kws-int8 等），不参与编译
- **踩坑记录**：
  - AsyncListDiffer 异步 currentList 曾导致消息顺序错乱/气泡重复（已修，见 f3fd4ca）
  - 流式渲染期间须跳过 Markwon，否则高频闪烁（见 bf57491）
  - RecyclerView itemAnimator 需关闭以消除刷新跳动（见 fb3c224）
  - 首次 bind 误判流式增量会跳过 Markdown 渲染（见 8ba915d）
