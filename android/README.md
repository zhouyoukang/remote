# 亲情远程 · 道法自然

> 一个APK + 一个网页 = 公网手机远程投屏+操控
> 手机到手机 · 端到端加密 · 零服务器 · 零费用 · 零学习成本

## 本源架构

```
父母手机 (Android APK)                        子女手机 (任意浏览器)
┌─────────────────────────┐                   ┌──────────────────────┐
│ MediaProjection 屏幕采集 │                   │ viewer.html          │
│ H.264 硬件编码           │◄── WebRTC P2P ──►│ 全屏视频 + 触控操作   │
│ AccessibilityService    │    端到端加密直连   │ 返回/主页/最近 导航   │
│ PeerJS 免费信令          │◄── DataChannel ──│ 点击/滑动/长按/缩放   │
└─────────────────────────┘                   └──────────────────────┘

信令: PeerJS 免费公共服务器 (仅交换连接信息, ~1KB)
穿透: Google STUN (免费) + Metered TURN (免费 500MB/月)
传输: WebRTC P2P 直连 (DTLS加密, 不经过任何中间服务器)
```

## 零依赖

| 不需要 | 需要 |
|-------|------|
| ❌ 自有服务器 | ✅ 两部手机 |
| ❌ 域名 | ✅ 双方有网络 (4G/5G/WiFi) |
| ❌ PC电脑 | ✅ 父母手机装APK (一次) |
| ❌ 局域网 | ✅ 子女手机开浏览器 |
| ❌ 账号注册 | |
| ❌ 付费 | |
| ❌ ADB/ROOT | |

## 使用流程

### 父母端 (一次安装)
1. 安装 `亲情远程.apk`
2. 打开APP → 点击 **「开始投屏」**
3. 允许屏幕录制权限
4. 屏幕显示 **6位房间号** + 二维码
5. 分享给子女 (微信/短信/截图)

### 子女端 (零安装)
1. 收到链接/房间号
2. 手机浏览器打开链接 (或输入房间号)
3. 立即看到父母手机画面
4. 点击/滑动即可远程操控

## 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| 屏幕采集 | MediaProjection API | Android原生, 无需ROOT |
| 视频编码 | H.264 硬件编码器 | GPU加速, 低功耗 |
| P2P传输 | WebRTC (google-webrtc) | 端到端加密, 自适应码率 |
| 信令交换 | PeerJS Cloud (0.peerjs.com) | 免费公共服务, 仅交换SDP |
| NAT穿透 | STUN (Google) + TURN (Metered) | 全免费, 覆盖对称NAT |
| 反向操控 | AccessibilityService | 注入点击/滑动/长按/缩放 |
| 数据通道 | WebRTC DataChannel | 操控指令<1ms传输 |
| 观看端 | 纯HTML单文件 | 任意浏览器, 零安装 |

## 项目结构

```
android/
├── app/
│   ├── build.gradle.kts              # 依赖: WebRTC + OkHttp + ZXing
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限: INTERNET + MediaProjection + Accessibility
│       ├── kotlin/com/dao/remote/
│       │   ├── App.kt                # Application: WebRTC初始化 + 通知渠道
│       │   ├── MainActivity.kt       # 主界面: 开始/停止 + 房间号 + QR码 + 分享
│       │   ├── CaptureService.kt     # 前台服务: MediaProjection生命周期
│       │   ├── PeerManager.kt        # 核心: WebRTC PeerConnection + 视频轨道 + DataChannel
│       │   ├── SignalClient.kt       # 信令: PeerJS WebSocket协议实现
│       │   └── ControlService.kt     # 无障碍服务: 触控注入 (tap/swipe/pinch/scroll)
│       ├── assets/
│       │   └── viewer.html           # 观看端: 单文件, 可独立部署到任意静态托管
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           └── xml/accessibility_config.xml
├── build.gradle.kts                  # AGP 8.2.2 + Kotlin 1.9.22
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties  # Gradle 8.5
```

## 构建

```bash
cd android
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## 部署 viewer.html

viewer.html 是完全独立的单文件, 可以:
- 放在 GitHub Pages (免费)
- 放在任何静态托管 (Netlify/Vercel/Cloudflare Pages)
- 直接本地打开 (file://)
- APK内嵌 (assets/viewer.html)

## 操控指令协议 (DataChannel JSON)

```json
{"type":"tap",       "x":540, "y":960}
{"type":"swipe",     "x1":540, "y1":1600, "x2":540, "y2":400, "duration":300}
{"type":"longpress", "x":540, "y":960, "duration":1000}
{"type":"pinch",     "cx":540, "cy":960, "scale":1.5}
{"type":"scroll",    "x":540, "y":960, "dx":0, "dy":-200}
{"type":"back"}
{"type":"home"}
{"type":"recents"}
{"type":"notifications"}
```

## 道法自然

> 上善若水。水善利万物而不争。
> — 《道德经》第八章

不与商业方案争:
- 不模仿 ToDesk / 向日葵 / 无界趣连
- 不依赖任何第三方基础设施
- 不需要用户学习任何新概念
- WebRTC 本身就是"水" — 自然流淌于任意网络之间
