# 网易云音乐 Lite · Android

[桌面版](../netease-lite) 的 Android 移植。Kotlin + Jetpack Compose，同一个账号、同一套接口；结构从「主进程 + 渲染进程」换成「一个 Activity + 一个前台播放服务」。

- 包名 `com.netease.lite`，`minSdk 26`（Android 8.0）/ `targetSdk 36`
- 竖屏为主，手机 / 平板 / 折叠屏都适配

## 功能

- **登录**：手机号 + 密码、短信验证码；可以先跳过，游客状态下推荐和搜索照常可用。密码本地先 MD5 再发。撞上账号级安全验证（`-462`）会弹二维码 + 浏览器验证链接，每 2.5 秒轮询，验完自动重登。
- **发现**：每日推荐、推荐歌单、榜单、播客、最近播放（落盘，关掉再开还在）。
- **搜索**：单曲 / 歌手 / 专辑 / 歌单 / 用户。
- **播放**：标准 / 较高 / 极高 / 无损四档；顺序 / 单曲循环 / 随机三档；后台播放、通知栏与锁屏控制、蓝牙耳机按键、音频焦点、拔耳机自动暂停；播放队列跨重启保留；逐行歌词（含翻译），点行跳播、长按复制。
- **下载**：MP3 三档 + 无损 FLAC，落盘进系统媒体库，离线可播。MP3 写入完整 ID3v2.3 标签（1080×1080 封面 + 官方 `COMM` 凭证），官方 App 能认出这是自己下的文件。支持断点续传（暂停 / 断网 / 杀进程后接着下同一个文件）、暂停继续、自定义下载目录与文件名模板、失败原因说人话、没版权 / 已下架单独提示。
- **本地**：SAF 扫一个目录按文件名认回本机的歌，本地文件优先播放；切音质时优先用本机已有的档位。
- **页面**：歌单、专辑、歌手（作品超 100 首可进「全部歌曲」）、用户、评论、关注 / 粉丝、听歌等级，歌单支持多选批量操作。
- **界面**：五款皮肤（经典黑 / 现代白 / 瑞士风 / 落日余晖 / 街机风），不止换色 —— 色带、按钮形状、硬投影、VIP 标识各有一套；全面屏适配，页面过渡与动效可关。

## 构建与安装

```bash
# 调试包
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 正式包
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

仓库不含签名文件：`keystore/netease-lite.p12` 缺失时 release 会退回 debug 签名，正式发布请自备 keystore 并填 `keystore/keystore.properties`。

## 项目结构

```
app/src/main/java/com/netease/lite/
  MainActivity.kt     入口、权限、启动路由
  AppViewModel.kt     全局状态：登录、歌单、页面栈、播放与下载调度
  api/                weapi 加密、Cookie、全部接口
  data/               下载器（断点续传、ID3 标签、MediaStore）、本地索引、播放器服务
  ui/                 Compose 界面：外壳与侧栏、各页面、播放页、主题与皮肤
  util/               格式化、Toast、日期等小工具
app/src/main/res/     图标、主题、字符串
```

## 说明

仅供个人学习与自用。音乐版权归网易云音乐及版权方所有，本客户端不存储、不再分发任何音频内容。
