# 亲情远程

> 一个APK + 一个网页 = 公网手机远程投屏+操控

**[立即使用 →](https://zhouyoukang.github.io/remote/viewer.html)**

## 核心特性

- **纯P2P** — WebRTC端到端加密直连，不经过任何中间服务器
- **零费用** — 使用免费公共STUN/TURN穿透，PeerJS免费信令
- **零安装** — 观看端只需浏览器打开网页
- **反向操控** — 点击/滑动/长按/缩放/导航
- **低延迟** — 硬件H.264编码 + P2P直连

## 使用

### 父母端（一次安装）
1. 安装 APK → 点击「开始投屏」
2. 允许屏幕录制权限
3. 分享6位房间号给子女

### 子女端（零安装）
1. 打开 [viewer.html](https://zhouyoukang.github.io/remote/viewer.html)
2. 输入房间号 → 连接
3. 看到画面即可操控

## 架构

```
父母手机 (APK)                          子女浏览器 (viewer.html)
MediaProjection ─► H.264 HW ─► WebRTC P2P 加密直连 ─► <video>
AccessibilityService ◄──── DataChannel JSON ◄──── 触控事件
```

## 构建

```bash
cd android
./gradlew assembleDebug
```

## 项目结构

- `viewer.html` — 观看端（GitHub Pages 托管）
- `android/` — Android APK 源码

详细文档见 [android/README.md](android/README.md)
