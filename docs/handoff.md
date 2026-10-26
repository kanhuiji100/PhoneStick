# PhoneStick 开发交接（更新至 2.3.7）

> 设备：Redmi K20 Pro (raphael)，Android 17 / SDK 37，kernel 4.14.357，Magisk 31.1
> 工程：`PhoneStick/` 2.3.7（versionCode 19）
> 交付：`交付/PhoneStick-2.3.7-cn.apk` + `交付/源码改动-2.3.7.patch`
> 配套：`README.md`（仓库根总览）、`design.md`（方案与 bug 复盘）、
> `troubleshooting.md`（早期根因）、`windows.md`（Windows 环境）

---

## 零、当前状态（2.3.7）

| 项目 | 状态 |
|---|---|
| 挂载 / 卸载 U 盘 | ✅ 真机验证 |
| 稀疏镜像创建（含裸盘） | ✅ 4GB 约 2 秒，8GB 实测 126MB 占用 |
| 容量 / 实际占用显示 | ✅ `8.00 GB · 已用 264 MB` / `2.00 GB · 未写入` |
| 释放未使用空间 | ✅ FAT32 / exFAT / NTFS / ext4，覆盖 MBR / GPT / 无分区表 |
| 中文界面 | ✅ 布局 + 对话框 + 菜单 + 通知 |
| 默认读写挂载 | ✅ `switchReadOnly` 默认关闭 |
| 遗留 | 仅 arm64；回收只处理第一个分区；ext4 `BLOCK_UNINIT` 组跳过 |

### 版本历史

| 版本 | 主要改动 |
|---|---|
| 2.3.1 | 镜像移到 `/data/media/0`（SELinux 可写）、MBR 分区数动态计算、格式化失败上报、Windows 驱动冲突定位 |
| 2.3.2 | 稀疏镜像（`truncate`），创建秒级、解决 >2GB OOM；显示实际占用 |
| 2.3.3 | 中文界面、默认读写、**释放未使用空间**（FAT32，`pstrim` 打洞） |
| 2.3.4 | 修复文件管理器永远显示旧大小（MediaStore/FUSE 滞后） |
| 2.3.5 | 修复裸盘镜像不被识别为稀疏（`used > 0` 判定错误） |
| 2.3.6 | 回收支持 exFAT / NTFS / ext4（初版，布局识别不全） |
| 2.3.7 | 回收补全 GPT / 无分区表支持，修正 ext4 位图寻址 |

> 以下「一 ～ 十二」按时间顺序保留了每一轮的排查过程与实测数据，
> 结论仍然有效，但**当前状态以本节为准**。

---

## 一、这一轮解决的核心问题

**Windows 主机完全看不到虚拟 U 盘，而 Linux / macOS 正常。**

根因不在手机、也不在 PhoneStick：手机把 `mass_storage,adb` 组合成
`05C6:9015`，大容量存储接口落在 `MI_01`（class 08/06/50）；这台 Windows 装了
手机厂的 ADB 驱动包，其中 OPLUS 的 `oem213.inf` 有一条按**硬件 ID**
认领 `USB\VID_05C6&PID_9015&MI_01` 的规则，于是 Windows 用 WinUSB 打开了
这个 MS 接口，USBSTOR 从未 attach → 没有磁盘。Linux/macOS 按接口类匹配，
所以同一份描述符在那边正常。

已在**主机侧**修复：备份并移除该驱动包后，`MI_01` 重新落到 `usbstor.inf`，
盘符立刻出现，adb 不受影响。

完整证据、修复命令、以及"为什么 App 侧绕不开"见
**`troubleshooting.md` 第十五节**；操作版见 **`windows.md` 5.5 节**。

---

## 二、当前验证状态

| 项目 | 状态 | 证据 |
|---|---|---|
| 手机侧 LUN / UDC / 配置链接 | ✅ | `lun.0/file` 有值，UDC `configured`，`function2 -> mass_storage.0` |
| Windows 枚举出 MS 接口 | ✅ | `USB\VID_05C6&PID_9015&MI_01`，compatible ID `Class_08&SubClass_06&Prot_50` |
| 修复后接口绑定 | ✅ | `Service=USBSTOR`，`DriverInfPath=usbstor.inf`（Microsoft） |
| 主机出盘 | ✅ | `E:` / `PSTEST` / FAT32 / 1021 MB |
| 主机写入真正落盘 | ✅ | 写入后手机侧 `grep -a` 镜像原始字节命中写入内容 |
| adb 不受影响 | ✅ | 修复全程 `adb devices` 正常 |
| 修复是否持久 | ✅ | 手机侧强制 UDC 解绑/重绑（完整重新枚举）后，盘符与 adb 都恢复 |
| App UI 的卸载 → 重挂流程 | ⏳ 未实测 | 本轮只做了 shell 层重新枚举 |
| App 内建「Create Blank Image」GUI | ⏳ 未实测（P3） | 底层 shell 已单独验证过 |
| SAF `content://` 导入 | ⏳ 未实测（P4） | — |
| >2GB 镜像 OOM | ⏳ 未实测（P5） | — |
| 卸载后 USB 配置恢复 | ⏳ 待确认（P6） | — |

---

## 三、这台 Windows 机器现在的状态（重要）

- `oem213.inf`（OPLUS, Inc. 的 Android ADB 驱动包）**已从驱动仓库删除**。
- 删除前已导出备份到 `tools/driver-backup/`（`android_winusb.inf` + `.cat` + 两个 DLL）。
- 需要还原时（例如以后接 OPPO / 一加 手机提示缺驱动）：

  ```powershell
  .\tools\fix-windows-usb-driver.ps1 -Restore
  ```

- 这台机器上另一个 INF `oem106.inf`（vivo, Inc.）负责 `MI_00` 的 ADB，
  **没有动**，所以删 OPLUS 那份不影响 adb。
- 手机已经从这台电脑拔掉；下次插上时应当直接出盘，不需要再做任何操作。

---

## 四、遗留问题与新发现

### 已改动代码：无

本轮**没有修改 `PhoneStick/` 源码**，`git status` 与本轮开始时完全一致
（工作区里原本就有那批 2.3.1 改动：`ImageStore.kt` 未跟踪、`.gitattributes` 未跟踪，
以及若干 M 文件，其中 `usbmountr.txt` / `gradlew` 的差异属打包与行尾产物，属正常）。

### 新发现（都已写进 `troubleshooting.md` 第十五节）

| # | 发现 | 影响 |
|---|---|---|
| A | App 自带的 `swy` gadget（Strategy 2）在 Android 17 上执行 `ln -s functions/mass_storage.0 configs/c.1/...` 报 `Operation not permitted` | Strategy 2 在这台设备上**根本挂不起来**，它作为 Strategy 1 的兜底是坏的，排查时会误导 |
| B | `setprop sys.usb.config mass_storage`（单 MS，理论上 `05C6:F000`）被 gadget HAL 忽略，主机侧仍是 `9015 + adb + MI_01` | 不能靠"去掉 adb"绕开 Windows 驱动冲突 |
| C | `/config/usb_gadget/g1/idProduct` 用 magisk 域写入返回 `Permission denied` | 不能改 PID 绕开 OEM INF |
| D | 这台机器 `sys.usb.state` **恒为空**（挂载成功时也空） | 不要用它判断挂载状态；`getMountStatus()` 继续用 `lun.0/file` + 配置链接即可 |
| E | `adb shell su -c "a; b; c"` 里分号后的命令经常以 `shell` 域执行，不是 root | 排查时曾误判成"SELinux 不让 root 读 configfs"；见指南 6.5 |
| F | 手机侧 `mount -t vfat` 回读正在被 gadget 使用的镜像会 `EINVAL`（整盘和 offset 都不行） | 校验"写入是否落盘"用 `grep -a` 镜像原始字节更可靠 |

### 待办（建议顺序）

1. **P2**（最值得修）：`getMountStatus()` 只检查 `lun.0/file` 非空就报告已挂载。
   建议增加：`configs/b.1/` 里是否存在 `mass_storage` 链接 + `getprop sys.usb.config` 是否包含 `mass_storage`。
2. **Strategy 2 决策**：要么按 Android 17 的 SELinux 限制重新设计（可能得改用
   `setprop` 路径或放弃自建 gadget），要么直接删掉，避免"兜底比主路径更糟"。
3. **Windows 提示**：App 无法判断主机 OS，但可以在挂载成功后提示
   "如果电脑上没有盘，运行 `tools\diagnose-windows-usb.ps1`"。
4. P3 / P4 / P5 / P6 按原计划在 Linux 侧继续。
5. 手机 `/data/local/tmp` 还有上一轮遗留的调试文件（`probe*.log`、`runfix.sh`、
   `_mbr.bin`、`pscheck/` 等）。`tools/cleanup-device.sh` 已扩充覆盖这些名字，
   下次连上手机跑一次即可：

   ```bash
   adb push tools/cleanup-device.sh /data/local/tmp/
   adb shell su -c "sh /data/local/tmp/cleanup-device.sh -n"   # 先预览
   adb shell su -c "sh /data/local/tmp/cleanup-device.sh"
   ```

   `phonestick_drive.img.bak` 和 `phonestick_prefs_backup.xml` 是备份，脚本**故意保留**。

---

## 五、回到 Linux 开发机后怎么继续

```bash
unzip PhoneStick-2.3.1-dev-20261026.zip -d phonestick-dev
cd phonestick-dev

# Git 仓库是完整的（浅克隆，1 个提交），可直接看 diff / 打补丁
git -C PhoneStick status
git -C PhoneStick diff --stat

# Windows 打包会丢可执行位，务必先补
chmod +x PhoneStick/gradlew

# 构建（首次要能访问 jitpack.io）
cd PhoneStick
./gradlew assembleRelease
```

注意：

- `PhoneStick/local.properties` **不在包里**，Linux 上如果没设 `ANDROID_HOME`
  需要自己建：`sdk.dir=/opt/android-sdk` 之类。
- 装机前确认签名：官方 2.3.0 与本仓库 `app/release.jks` 不同，
  `adb install -r` 会失败，先 `adb uninstall mingww64.phonestick`。
- `tools/*.ps1` 是 Windows 专用，Linux 上用不到；
  `tools/fix-windows-usb-driver.ps1` 的备份目录 `driver-backup/`
  没有打进压缩包（属于那台 Windows 机器，留在原机即可）。
- shell 脚本均为 LF；`.gitattributes` 已固定 `*.sh text eol=lf`。

### 三条快速回归（在 Windows 上）

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\diagnose-windows-usb.ps1   # 应输出 drive E: PSTEST
adb shell su -c "cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"
Get-Disk | Where-Object BusType -eq 'USB'
```

> 双击/直接执行 `.ps1` 可能被默认执行策略拦住，加
> `powershell -ExecutionPolicy Bypass -File` 前缀最稳。

---

## 六、本轮文件变更

| 文件 | 变更 |
|---|---|
| `tools/diagnose-windows-usb.ps1` | **新增**：只读诊断（adb → LUN → 配置链接 → MS 接口绑定 → 盘符） |
| `tools/fix-windows-usb-driver.ps1` | **新增**：备份 + 移除抢占 MS 接口的 OEM ADB 驱动包；`-Restore` 还原 |
| `tools/driver-backup/` | **新增**：上述脚本导出的驱动包备份（未打进压缩包） |
| `tools/cleanup-device.sh` | 扩充清理名单（probe*、runfix.sh、pscheck 等），支持目录与 configfs 探针目录，备份文件显式保留 |
| `windows.md` | 新增 5.5 节；判读表 / 故障对照表 / 工具清单 / P1 更新；6.5 增加 `su -c` 分号坑 |
| `troubleshooting.md` | 新增第十五节（根因、证据、修复、三条绕不开的路）；第十三节加 P7 |
| `handoff.md` | **新增**：本文件 |
| `PhoneStick/` | 未改动 |
| `交付/` | 未改动 |

---

## 七、时间线（本轮）

| 时间 | 事件 |
|---|---|
| 11:20:54 | App 走 Strategy 1 挂载成功（`mass_storage,adb`，LUN 指向 qqq.img） |
| 11:21:40 | Windows 枚举 `05C6:9015`，`MI_01` 被 `oem213.inf` 绑成 WinUSB |
| 11:24–11:40 | 排查：确认接口类、INF 规则、HAL/ConfigFS 限制 |
| 11:47 | 实测 Strategy 2（`swy`）失败：`ln -s` EPERM |
| 11:50 | 实测 `mass_storage` 单组合被 HAL 忽略 |
| 11:57 | `fix-windows-usb-driver.ps1` 执行：备份 → 删除 `oem213.inf` → 重扫 |
| 11:58 | 盘符 `E:`（PSTEST，FAT32，1021MB）出现，adb 正常 |
| 11:59 | 主机写入 → 手机侧 grep 镜像，确认落盘 |
| 12:02 | UDC 解绑/重绑后重新枚举，盘符与 adb 均恢复（修复持久） |
| 12:10 | 清理临时文件、补文档、打包 |

---

## 八、2.3.2：稀疏镜像（2026-10-26 下午，已真机验证）

### 做了什么

把镜像创建从 `fallocate`/分块 `dd` 改成 **`truncate` 稀疏分配**：
逻辑容量还是用户选的大小，物理占用随主机写入量增长。
`fallocate`/`dd` 保留为「取消勾选稀疏」时的预分配路径。

| 文件 | 改动 |
|---|---|
| `ImageCreator.kt` | 新增 `sparse` 参数，默认 `truncate -s <bytes>`，失败回落预分配 |
| `ImageStore.kt` | `Info` 增加 `allocatedBlocks`/`usedBytes`/`isSparse`（`stat %b`），新增 `formatSizeWithUsage()` |
| `ImageFilesAdapter.kt` / `MainActivity.kt` | 显示「容量 · 实际占用」 |
| `ImageChooserActivity.kt` / `dialog_create_image.xml` | 开关改为 "Sparse image (allocate on demand)"，加说明文案 |
| `app/build.gradle` | 2.3.2 / versionCode 14 |

### 验证结果（同一台 K20 Pro）

- 8GB / FAT32 稀疏镜像：点击创建后 **约 2 秒**完成，`du` 仅 **126MB**（FAT 表成本）
- UI：`8.00 GB · 126.17 MB used`
- App 内挂载 → 主机 `sdb` 8.00 GiB、`sdb1` vfat；写 100MB 用时 5.4s，md5 一致，手机 `du` 精确 +100MB
- **P5（>2GB 镜像 OOM）关闭**
- 测试后已还原：镜像删除、LUN 空、`sys.usb.config=adb`

### 产物

- `交付/PhoneStick-2.3.2-sparse.apk`（已装机验证，vc14）
- `交付/源码改动-2.3.2.patch`（含 2.3.1 以来的全部改动，12 个文件）
- `design.md` 第八节：实现细节与验证数据；附录含 qcow2/VDI 不可行的完整论证

### 注意

- 只读开关默认 `checked=true`（既有行为），App 挂出来的盘默认写保护。
- toybox `dd` 没有 `conv=sparse`，手机侧复制镜像一定膨胀；保稀疏请在 PC 上做
  （`cp --sparse=always` / `rsync -S` / `qemu-img convert -O raw`）。

---

## 九、2.3.3：中文化 + 默认读写 + 空间回收（2026-10-26 晚）

### 1. 默认读写

`activity_main.xml` 的 `switchReadOnly` 从 `checked="true"` 改为 `false`，
App 挂载出来的盘默认可写（LUN `ro=0`）。其他语言/逻辑不变。

### 2. 中文化

- `res/values/strings.xml` 全部译为中文（key 不变，de/fr/it/lt 目录未动）；
- 各 layout 里写死的英文（创建/导入/格式化按钮、Splash 的 Root 提示、
  创建对话框的预设容量/文件系统/稀疏开关、列表 "ACTIVE"）全部换成中文；
- Kotlin 里的对话框、Snackbar、菜单、通知文案（`ImageCreator`、`ImageChooserActivity`、
  `ImageFilesAdapter`、`UsbGadgetController`、`MainActivity`）逐条中文化。
- 实测截图文字：`USB 大容量存储状态`、`只读模式`、`挂载 U 盘`、`选择或创建磁盘镜像`、
  `4.00 GB · 63.09 MB used`。

### 3. 释放未使用空间（新功能）

镜像列表 → 每项右上角菜单 → **释放未使用空间**（仅稀疏镜像显示）。

实现：`tools/pstrim.c` 交叉编译成静态 aarch64（700KB），打进 `assets/pstrim`，
运行时释放到 `/data/local/tmp/pstrim` 以 root 执行；解析 MBR+FAT32，
找出 FAT 里值为 0 的空闲簇，按 4K 对齐 `fallocate(PUNCH_HOLE)`，
顺带打掉头部全零 4K 块。不重写数据、不需要额外空间。

实测（4GB 稀疏镜像）：
- 写入 400MB 后删 300MB → 仍占 463.49MB（问题复现）
- 菜单执行后 → **102.06MB**（剩 100MB 保留文件 + FAT 元数据）
- 重新挂载：保留文件 md5 一致，fsck 结构正常

限制：只支持 FAT32；镜像挂载中会提示先卸载。

### 产物

- `交付/PhoneStick-2.3.3-cn.apk`（vc15，已装机）
- `交付/源码改动-2.3.3.patch`（18 个文件，含 2.3.1 以来全部改动）
- `design.md` 第九节：回收方案与被否掉的替代实现

> 注意：patch 不含 `assets/pstrim`（二进制）。重新编译命令：
> `aarch64-linux-gnu-gcc -static -O2 -o pstrim pstrim.c`（源在 `tools/pstrim.c`），
> 产物放到 `app/src/main/assets/pstrim`。

---

## 十、2.3.4：修复「文件管理器显示 2GB」+ 中文化补漏

**根因**：镜像由 root 直接在 `/data/media/0` 下重建，MediaProvider 不知情，
同名文件的旧记录（`_size`）一直没刷新，FUSE/文件管理器就永远显示旧大小。
与「裸盘/稀疏」无关——实测裸盘镜像本身是稀疏的（逻辑 6GB、占用 0）。

**修复**：新增 `ImageStore.syncFuseMetadata()` / `repairFuseMetadata()`，
通过 `/sdcard` 路径做一次同长度 `truncate` 让 MediaProvider 更新记录；
删除/重命名改走 FUSE 路径；列表加载时自愈历史脏记录；
稀疏创建失败不再悄悄回落预分配，改为明确报错。

**实测**：1GB→删→同名重建 3GB，文件管理器立即显示 3GB；删除后 MediaStore
无残留；后台改小后打开列表自动校正。

**补漏**：删除确认框残留的 "Cancel"、以及 7 处 contentDescription 也一并中文化。

产物：`交付/PhoneStick-2.3.4-cn.apk`（vc16）、`交付/源码改动-2.3.4.patch`。

---

## 十一、2.3.5：裸盘镜像的稀疏识别

判定 bug：`used > 0 && used < size * 95%` 把「0 占用」的裸盘镜像误判成非稀疏，
导致既不显示「未写入」，也没有「释放未使用空间」菜单。

改为 `ImageStore.isSparse(size, used) = size > 0 && used < size`，
列表/菜单共用；`used == 0` 显示 `· 未写入`；空镜像点回收直接提示
「该镜像没有可释放的空间」；非 FAT32 的错误文案改为可操作建议。

实测：2GB 裸盘 → `2.00 GB · 未写入` + 有菜单；FAT32 稀疏镜像照常；
预分配镜像不显示菜单。

产物：`交付/PhoneStick-2.3.5-cn.apk`（vc17）、`交付/源码改动-2.3.5.patch`。

---

## 十二、2.3.7：回收功能支持 exFAT / NTFS / ext4

`tools/pstrim.c` 重写为多文件系统工具：
- FAT32（FAT 表）、exFAT（Allocation Bitmap）、NTFS（$Bitmap via MFT runlist）、
  ext4（block bitmap，支持 meta_bg，BLOCK_UNINIT 组跳过）；
- 分区布局支持 MBR / GPT / 超级软盘（无分区表）。

修复的坑：ext4 超块 `incompat` 偏移写错（0x5C→0x60）导致把 RESIZE_INODE 当
META_BG、曾把在用块打洞（md5 抓出）；GPT 头字段偏移 88/96/100→72/80/84；
几何校验混用整盘/分区偏移。设备上 `losetup -o` 读返回 0 字节，FITRIM 路线不可行。

验证：4 FS × 3 布局 = 8 组合（MBR/GPT/超级软盘）全部通过，整理后重新挂载
md5 一致；真机 ext4 镜像 382.57MB → 82.50MB，e2fsck 干净；
现有 fat/exfat/ntfs/ext4 四个真实镜像 dry-run 全部正确识别。

产物：`交付/PhoneStick-2.3.7-cn.apk`（vc19）、`交付/源码改动-2.3.7.patch`。
