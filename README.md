# PhoneStick 📱💾

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" height="128" alt="PhoneStick Icon">
</p>

PhoneStick turns your rooted Android device into a USB Mass Storage drive or CD-ROM emulator using Android Kernel ConfigFS and USB gadget drivers.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_main.png" width="360" alt="PhoneStick Screenshot">
</p>

---

> **本仓库是 [`mingww64/PhoneStick`](https://github.com/mingww64/PhoneStick) 的个人分支（fork）。**
> 上游基线为 `v2.3.1-beta`（2.3.0 系列），本分支在其之上修复了镜像位置、分区表、
> 稀疏占用等一系列问题，并做了中文化、空间回收等增强，当前版本 **2.3.7**（versionCode 19）。
>
> - 安装包在 [Releases](https://github.com/kanhuiji100/PhoneStick/releases) 下载（仓库内不存 APK）
> - 与原版签名不同，覆盖安装前需先 `adb uninstall mingww64.phonestick`
> - 所有改动集中在 `docs/` 里留档，便于差异追溯与回归

## 本分支相对上游的改动

| 版本 | 主要改动 |
|---|---|
| 2.3.1 | 镜像移到 `/data/media/0`（SELinux 可写）；MBR 分区数动态计算；格式化失败上报；定位 Windows 驱动抢占 MS 接口 |
| 2.3.2 | **稀疏镜像**（`truncate`）：8GB 镜像创建约 2 秒、占用 126MB，解决 >2GB OOM；列表显示实际占用 |
| 2.3.3 | **中文界面**；挂载默认读写；新增**释放未使用空间**（FAT32，`fallocate(PUNCH_HOLE)` 打洞） |
| 2.3.4 | 修复文件管理器永远显示旧大小（MediaStore / FUSE 记录滞后），列表加载时自愈 |
| 2.3.5 | 修复裸盘镜像不被识别为稀疏（`used > 0` 判定错误） |
| 2.3.6 | 空间回收支持 exFAT / NTFS / ext4（初版，布局识别不全） |
| 2.3.7 | 回收补全 GPT / 无分区表；修正 ext4 位图寻址与 GPT 头偏移 |

细节见 [`docs/handoff.md`](docs/handoff.md)（逐版本改动与实测数据）、
[`docs/design.md`](docs/design.md)（稀疏镜像与打洞回收的方案论证）。

## Features

上游能力：

- **Material Design 3**：动态明暗主题、悬浮操作按钮。
- **Mount Raw Disk Images**：直接把 `.img` / `.iso` / `.bin` / `.raw` / `.vhd` / `.qcow2` 挂成 U 盘或光驱。
- **Zero-Copy SAF Resolution**：把外部存储 URI（SD 卡、下载目录）解析成内核可直接使用的路径，不复制文件。
- **Emulation Modes**：只读模式与虚拟 CD-ROM。
- **ConfigFS & Sysfs Support**：同时支持 `/config/usb_gadget/g1`、`/sys/kernel/config` 与 sysfs LUN。
- **Blank Image Creation**：App 内创建并格式化空白镜像。

本分支新增：

- **稀疏镜像**：逻辑容量固定、物理占用按写入量增长，创建秒级完成。
- **释放未使用空间**：按文件系统位图找出空闲簇并打洞，不重写数据、不需要额外空间。
  支持 **FAT32 / exFAT / NTFS / ext4**，分区布局支持 **MBR / GPT / 无分区表**。
- **中文界面**：布局、对话框、菜单、通知全中文。
- **容量与占用显示**：`8.00 GB · 已用 264 MB`、`2.00 GB · 未写入`。

## Requirements

- 已 root 的 Android 设备（Magisk / KernelSU / APatch）
- 内核支持 USB Mass Storage gadget（`CONFIG_USB_F_MASS_STORAGE` 或 ConfigFS）
- `minSdk 24`，`targetSdk 34`，`compileSdk 34`

## 构建

仓库根目录就是 Android 工程根，可直接用 Android Studio 打开。

```bash
# 方式一：容器（推荐，环境干净；Dockerfile 含 Android SDK 34 + JDK 21）
docker build -t ps-build sdkbuild
docker run --rm -v "$PWD:/work" -w /work ps-build \
  bash -lc "chmod +x gradlew && ./gradlew assembleRelease"

# 方式二：本机（需要 Android SDK 34 + JDK 17/21）
echo "sdk.dir=/opt/android-sdk" > local.properties
chmod +x gradlew
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

`app/src/main/assets/pstrim` 是随包分发的 aarch64 静态可执行文件，**构建时必需**，
仓库中已提供，一般不用重新编译。需要改源码时：

```bash
aarch64-linux-gnu-gcc -static -O2 -o pstrim tools/pstrim.c
cp pstrim app/src/main/assets/pstrim
```

签名使用仓库内的 `app/release.jks`（与上游一致），密钥口令见 `app/build.gradle`。

## 安装

```bash
adb uninstall mingww64.phonestick        # 签名与上游正式版不同，覆盖安装会失败
adb install PhoneStick-2.3.7-cn.apk      # 从 Releases 下载
```

## 使用要点

1. 选择或创建镜像（建议勾选稀疏）→ 挂载 U 盘。
2. 挂载后默认**读写**，需要只读时打开「只读模式」。
3. 删除文件后宿主侧占用不会自动下降，用列表项菜单里的「**释放未使用空间**」回收。
   回收前需先卸载，否则会提示镜像被占用。
4. 电脑上没出现盘符？先跑诊断脚本：
   `powershell -ExecutionPolicy Bypass -File .\tools\diagnose-windows-usb.ps1`

## 文档与工具

| 路径 | 内容 |
|---|---|
| [`docs/handoff.md`](docs/handoff.md) | 版本演进（2.3.1 → 2.3.7）、每轮改动、遗留问题与待办 |
| [`docs/design.md`](docs/design.md) | 稀疏镜像方案研究、空间回收实现、几轮 bug 的根因与验证数据 |
| [`docs/troubleshooting.md`](docs/troubleshooting.md) | 早期根因排查（SELinux 写权限、MBR 截断、Windows 驱动抢占接口） |
| [`docs/windows.md`](docs/windows.md) | Windows 侧环境搭建、构建、验证与故障对照表 |
| `tools/pstrim.c` | 多文件系统打洞工具源码（随包二进制的来源） |
| `tools/diagnose-windows-usb.ps1` | Windows 只读诊断：adb → LUN → 配置链接 → MS 接口绑定 → 盘符 |
| `tools/fix-windows-usb-driver.ps1` | 备份并移除抢占 MS 接口的 OEM ADB 驱动包，`-Restore` 还原 |
| `tools/device-doctor.sh` | 手机侧一键体检 |
| `tools/cleanup-device.sh` | 清理调试遗留文件（备份文件显式保留） |
| `sdkbuild/Dockerfile` | 容器化构建环境 |

## 已知限制

- `pstrim` 只编译了 **arm64-v8a**，其他架构会提示不支持。
- 空间回收只处理**第一个分区**；ext4 的 `BLOCK_UNINIT` 组保守跳过（可能少回收）。
- 镜像在手机内部复制（`cp` / `dd`）会**丢失稀疏性**——toybox 没有 `conv=sparse`；
  需要保稀疏请在 PC 上操作（`cp --sparse=always` / `rsync -S` / `qemu-img convert -O raw`）。
- `/sdcard` 是 FUSE 视图，MediaStore 记录可能滞后；App 已做同步与自愈，
  但用电脑直接改镜像之后，文件管理器里的大小要等 App 打开列表才刷新。
- 部分 OEM 的 ADB 驱动包（如 OPLUS `oem213.inf`）会按硬件 ID 抢占大容量存储接口，
  导致 Windows 看不到盘。属主机侧问题，见 `docs/troubleshooting.md` 第十五节。

## 与上游同步

```bash
git remote add upstream https://github.com/mingww64/PhoneStick.git   # 如未添加
git fetch upstream
git rebase upstream/master
```

若本地仓库是浅克隆（从 zip 打包环境带过来的，`.git/shallow` 存在），
`rebase` 前先 `git fetch --unshallow` 补全历史。

本分支为保持可读性，提交按「源码 / 文档 / 构建环境」分开，rebase 时冲突一般集中在
`app/build.gradle`（版本号）与 `strings.xml`（翻译）。

## License

[MIT License](LICENSE)

Original work by streetwalrus, dratini0, donfanning, Swyter, and JinbaIttai.
