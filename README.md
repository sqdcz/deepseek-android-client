# DeepChat for Android

非官方 DeepSeek 安卓客户端。Kotlin + Jetpack Compose 写的，界面尽量贴官方，功能上补了一些官方客户端没有的：本地离线语音识别、全局系统提示词、记忆、官方数据导出包导入。

> 不是深度求索的官方项目，也不做任何商业用途。

## 功能

**对话**

- 流式输出，可随时中断
- **消息分支**：重新生成与编辑提问都会生成新分支，用 `‹ 2/3 ›` 在版本间切换
- 深度思考（`thinking` / `reasoning_content`），思考过程可折叠
- 联网搜索（`deepseek_web_search` / `deepseek_read_url`），引用来源可点
- 图片输入（多模态）
- Markdown、代码块高亮、LaTeX 公式、表格
- 多会话、置顶、全文搜索、重命名、导出 Markdown
- 双协议：DeepSeek 官方走 Anthropic 兼容接口（服务端生成标题、风控、引用块），第三方走 `/chat/completions`

**语音**

- 输入区三种形态：默认占位 / 按住说话 / 编辑，点麦克风切换
- 识别引擎三选一：系统识别、本地 Vosk 模型（离线）、DeepSeek 接口
- 识别结果默认填进输入框，可改成直接发送
- TTS 朗读回答，支持系统 TTS 与 DeepSeek 接口音色

**模型与数据**

- 多服务商：DeepSeek 官方、任意 OpenAI 兼容中转
- 模型列表可从 `/models` 拉取，支持连接测试与错误码解读
- 全局系统提示词 + 会话级覆盖
- 记忆条目，会拼进系统提示词
- WebDAV 同步
- 配置导出/导入（json）
- 导入官方数据包 `deepseek_data-*.zip`
- 从 DeepSeek 分享链接导入对话

## 构建

需要 JDK 21 和 Android SDK 35。

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

发布签名读取 `keystore.properties`（已在 `.gitignore` 里），格式见 `keystore.properties.example`。没有这个文件时会退回 debug 签名，照样能编译。

## 项目结构

```
app/src/main/java/com/shipyard/deepchat/
├── data/       模型定义、本地存储（SharedPreferences + 私有目录）
├── net/        SSE 流式对话、语音接口、官方包解析、分享链接解析、备份
├── audio/      录音、本地 ASR（Vosk）、系统 ASR、TTS
├── vm/         ChatViewModel（单状态源）
└── ui/         Compose 界面，无第三方 UI 库
```

## 隐私

- API Key、对话记录、记忆、设置全部存在手机本地，不上传任何服务器
- 语音：选本地模型时完全离线；选接口时才会上传到 DeepSeek
- 没有任何统计、崩溃上报、广告 SDK
- 第三方依赖只有 OkHttp、Coil、kotlinx.serialization、Vosk

## 已知问题

| 问题 | 说明 |
|---|---|
| 部分机型没有系统语音识别 | 如 OPPO PESM10，`SpeechRecognizer.isRecognitionAvailable()` 返回 false，需改用本地模型或接口 |
| Vosk 模型下载慢 | 官方源 `alphacephei.com` 国内单线程只有 6.5 KB/s，42MB 约 1.8 小时。建议电脑下好解压后，用设置里的「选文件夹导入」 |
| 应用体积 | 带 Vosk 后 21MB（已过滤到 arm64-v8a / armeabi-v7a） |

## 免责声明

- 本项目与深度求索（DeepSeek）无关，非官方客户端
- `app/src/main/res/drawable/ic_official_*.xml` 这些图标提取自 DeepSeek 官方 APK，版权归深度求索所有，这里仅用于界面还原。若权利方要求，会立即移除
- "DeepSeek" 及相关标识是深度求索的商标
- 请使用自己的 API Key，遵守 DeepSeek 的服务条款

## License

MIT，见 [LICENSE](LICENSE)。
