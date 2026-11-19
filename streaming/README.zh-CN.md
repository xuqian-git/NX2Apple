# NXStream 原型

这是 xuqian-git/NX2Apple 的实验性局域网串流分支。游戏仍在 iPhone 上运行，安卓车机或电视接收画面和声音。

## 当前范围

- H.264 硬件编码，输出 1920×1080、2560×1440、2880×1620，最高发送节奏 60 fps。
- NVN 游戏最终纹理接入，默认不在手机上显示游戏画面，保留前台运行。锁屏或退到后台会暂停。
- 输出尺寸与游戏内部原生渲染尺寸不同。串流模块按所选尺寸缩放，游戏原生分辨率由其 profile 和 DM_RENDER_SCALE 决定，不能保证所有游戏原生 1620p。
- PCM 48 kHz，保留游戏实际输出的立体声或六声道。协议预留 8/12 声道，但不会把 5.1 伪装成原生 7.1.4，也不生成 Dolby Atmos。
- 安卓原生硬件解码和 Surface 显示。音频只有在系统报告可用的多声道直通能力时才尝试相应 AudioTrack，否则混成立体声。原车 DSP 私有接口未接入。
- IPv4 UDP，同一可信局域网。会话 token 仅用于隔离串流，不提供加密或抵御同网段攻击。不要做公网端口映射。

## 安装和连接

1. 从本 fork 的 Actions 或 Releases 下载 NXStream Android APK，安装在车机或电视，允许系统询问的网络权限。
2. 打开接收端，记下屏幕显示的局域网 IP 和 16 位十六进制 token，按 Start receiving。手柄直接连接 iPhone。
3. 下载本 fork 的 macOS 构建。它使用不同 bundle ID，带有修改后的引擎。使用自己的游戏文件重新构建游戏 host framework 并导出 iOS IPA，按原项目方式自行签名安装。旧 IPA 不会因为换了安卓接收端就获得串流功能。
4. 在 iPhone 文件 App 的 NX2Apple Documents 下的 `env.txt` 中添加下方内容（也可使用项目原有的每游戏 env.txt）。IP 和 token 必须替换为车机显示的值。
5. 重启游戏，在 iOS 本地网络权限提示中允许访问。应用保持前台，不按电源键锁屏。安卓画面点一下可显示/隐藏控制面板。

```ini
DM_STREAM_HOST=192.168.1.100
DM_STREAM_TOKEN=替换为接收端的16位十六进制token
DM_STREAM_SIZE=1920x1080
DM_STREAM_BLACK=1
DM_PRESENT_LINK=0
DM_SCREEN_ASPECT=0
```

先测 1080p，再改为 `2560x1440` 或 `2880x1620`。三档均为 16:9。`DM_STREAM_BLACK=0` 可保留手机本地画面。删除 `DM_STREAM_HOST` 后恢复正常游戏路径。声音异常时勾选安卓端 Force stereo 并重新连接。UDP 端口为 48000。

## 验证与限制

已进行的自动化检查会记录在 Actions：协议乱序/重复/超时/恶意长度测试，安卓 APK 编译，iOS 发送模块 SDK 编译检查，修改后的完整 macOS 游戏 host 编译，Mac launcher 构建和打包。
本机另有真实 Metal + VideoToolbox 回环测试，三档分辨率 H.264 解码成功，六声道 PCM 保持声道数据。

尚未证明：具体 iPhone 的游戏内表现、车机硬件解码/多声道实际路由、长时间热稳定性、音画同步漂移、端到端延迟。没有游戏内容的 Actions 不进行实际游戏测试。不将编译成功视为超低延迟达标。

当前只接 NVN 最终呈现路径，不承诺 Vulkan/OpenGL 游戏。SDR 首版，不承诺 HDR 色彩。音频抓取一个最终输出流，多输出设备并行混音尚未实现。原型不支持安卓端回传手柄输入、自动发现、动态码率和网络拥塞自适应。网络丢包会请求 IDR，视频队列和音频队列有上限，严重拥塞应降低分辨率。

## 构建与维护

- `streaming/check.sh`：协议测试和 iOS 模块编译检查。
- `gradle -p streaming/android assembleDebug`：安卓 APK，需 Gradle 8.13、JDK 17+、Android SDK 35。
- `python3 tools/engine.py sync` 后用 `tools/engine.py tree --out <目录>` 导出引擎，再运行 `python3 streaming/overlay.py <目录>`。overlay 对上游接入点做精确匹配，不匹配就失败，不静默跳过。
- `streaming/build_mac.sh <绝对输出目录>` 在一次性 checkout 中构建 Mac app（会调整此 checkout 的启动器编译输入）。
- Fork 工作流每日新加坡时间 04:30 检查 LNPANDY/NX2Apple main。合并候选先构建验证，全部成功才合入 fork main，冲突或构建失败保留 main。定时触发时间可能被 GitHub 延后。
- Actions 安卓产物使用此 fork 专用的稳定签名密钥，可覆盖安装后续同签名构建。本地 assembleDebug 是另一调试签名，不可直接覆盖 release 版。
