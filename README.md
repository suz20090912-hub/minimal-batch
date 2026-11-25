<div align="center">

# 极简批处理 · Minimal Batch

手机端纯本地批量图片处理：压缩 / 改尺寸 / HEIC→JPG / 重命名

无账号、无广告、不上传云端，一条管线三步走完

</div>

---

## 为什么做这个

相册里一堆 HEIC 发不出去、微信一发就压成马赛克；网盘和修图 App 要么让你登录，要么先把你几十张照片传上云，要么为了四个功能塞进两百兆的界面。

所以做了这个：**只做四件事，只在你手机里做。**

## 它只干四件事

| 功能 | 说明 |
|---|---|
| 压缩 | 按目标长边重编码 JPEG，质量可调 |
| 改尺寸 | 长边缩到 1920 / 1080，或保持原尺寸 |
| 转格式 | HEIC / PNG / WebP → 通用 JPG |
| 重命名 | 统一前缀 + 序号，如 `旅行_001.jpg` |

## 三个场景预设

不用懂参数，选场景就行：

- **微信发送** — 长边 1920，JPEG q82，发出去不糊、不被二次压坏
- **存档原图** — 保持原尺寸，q90，只把 HEIC 换成通用 JPG
- **社交方图** — 中心裁成 1080 方图，q85，做头像和种草图

## 三步走完

1. 选图（一次可选几十张）
2. 选场景（或填一个重命名前缀）
3. 点「开始处理」

跑完给你一张总账：原图多少 MB → 现在多少 MB、省了几个百分点；每张的尺寸和体积都能核对。
不满意就点 **「撤销这批」**，把刚写出去的图删干净 —— **原图永远只读，不会被覆盖。**

## 实测数据（vivo V2302A，Android 16，天玑 9200 级）

12 张 5000 万像素（8192×6144）原图，3 路并行：

| 方案 | 12 张总耗时 | 单张位图内存 | 进程峰值 |
|---|---|---|---|
| 整张解码再缩放 | 2.09s | 192MB | 552MB |
| **解码阶段就按 1920 采样** | **1.59s** | **10MB** | **311MB** |

结论：**耗时的大头不是解码，而是"把整张图铺成位图再缩回去"**。所以这个 App 从解码器上就按目标尺寸采样，速度更快、内存省一半以上。

更详细的测量方法和数据见 [docs/measurements.md](docs/measurements.md)。

## 安装

到 [Releases](../../releases) 下载 APK 安装即可（minSdk 29 / Android 10 及以上）。

> v1.0 是 debug 签名的测试包，功能可用，后续会补正式签名。

## 从源码编译

需要 JDK 17 与 Android SDK（API 35）：

```bash
git clone https://github.com/suz20090912-hub/minimal-batch.git
cd minimal-batch
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。
用 Android Studio 打开也行：`File → Open` 选仓库根目录，等 Sync 完直接 Run。

## 目录结构

```
minimal-batch/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/localbench/batch/
│       │   ├── Pipeline.kt       # 解码 / 缩放 / 编码 / 写图库 / 撤销
│       │   └── MainActivity.kt   # Compose 界面
│       └── res/values/{strings,themes}.xml
├── gradle/wrapper/               # Gradle 8.9
├── docs/measurements.md          # 为什么这样设计（含实测）
└── LICENSE
```

## 设计取舍

- **不做"全能修图"**：滤镜、拼图、去水印都不做。只解决"批量把图变小、变通用"。
- **不覆盖原图**：所有输出都是新文件，写进 `Pictures/极简批处理`，撤销就是删新文件。
- **不申请存储权限**：走 MediaStore，Android 10+ 无需任何权限即可写入自己的输出。
- **不联网**：没有网络权限，图片不出手机。
- **不带历史记录**：v1 刻意不存任何状态，退出即清空，减少 bug 面。

## Roadmap

- [ ] 文件夹批量导入、跳过已达标文件
- [ ] 输出目录可配置、可选 PNG/WebP
- [ ] 处理中断后继续（任务清单落盘）
- [ ] 正式签名包 + 上架 F-Droid
- [ ] 英文界面

## 已知限制

- 只输出 JPEG，暂不支持输出 HEIC/PNG/WebP
- 单进程 3 路并行，超大图（>50MP）批量时仍可能触发系统内存回收
- v1 无历史记录，退出 App 后本批结果不再显示（输出文件仍在）
- 截图待补

## 隐私

全部处理在本机完成，App 不声明网络权限，不上传、不收集任何数据。

## 许可证

[MIT](LICENSE)
