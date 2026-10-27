# PhoneStick 2.3.1 Windows 迁移调试指南

> **文档状态**：面向 2.3.1 的 Windows 环境搭建与验证流程，操作步骤仍然适用；
> 项目当前版本是 **2.3.7**，新增能力（稀疏镜像、空间回收、中文界面）见 `README.md`。
> 配套文档：`排查报告.md`（问题根因与已完成的修复）、`进度与交接.md`（当前状态 / 本机改动 / 遗留待办）
> 目标：把工程搬到 Windows 上继续排查剩余问题

- 设备：Redmi K20 Pro (raphael)，Android 17 / SDK 37，kernel 4.14.357，Magisk 31.1
- 工程：`PhoneStick/`（已含 2.3.1 全部改动，`.git` 完整保留）
- 已验证可用：APK 编译、安装、镜像导入/选中、分区表动态计算、主机侧格式化与读写

---

## 一、包内结构

```
PhoneStick-2.3.1-dev/
├── Windows迁移调试指南.md      ← 本文件，先看这个
├── 排查报告.md                  ← 根因分析 + 修复清单 + 遗留问题
├── 进度与交接.md                ← 当前进度、这台 Windows 改了什么、下一步从哪继续
├── 交付/
│   ├── PhoneStick-2.3.1-fixed.apk   已编译好，可直接装
│   └── 源码改动.patch                git apply 可复现全部改动
├── PhoneStick/                  ← 源码工程（含 .git，1.4MB）
│   └── app/release.jks          签名文件，密码见第四节
├── tools/                       ← 命令行排查工具
│   ├── device-doctor.sh         设备一键体检（在手机上跑）
│   ├── mount-and-verify.ps1     Windows 侧挂载并验证主机是否出盘
│   ├── cleanup-device.sh        清理设备上遗留的临时文件
│   ├── phonestick_parts.py      手工生成 MBR/分区头（任意容量）
│   ├── phonestick_fix.sh        手工修复脚本（搬移+分区表+补链接+重绑）
│   ├── switch_lun.sh            单纯切换 LUN 到指定镜像
│   ├── diagnose-windows-usb.ps1 只读诊断：adb/LUN/配置链接/MS 接口绑定/盘符，逐项给结论
│   └── fix-windows-usb-driver.ps1 Windows 驱动冲突修复（需管理员，可 -Restore 还原）
└── sdkbuild/
    └── Dockerfile               Linux 容器构建环境（Windows 上不需要，仅供参考）
```

> `tools/device-doctor.sh` 和 `cleanup-device.sh` 输出全英文，任何控制台都能读。
> `phonestick_fix.sh` / `switch_lun.sh` 的日志是中文（UTF-8），在 cmd 里可能显示成乱码，
> 先执行 `chcp 65001` 切到 UTF-8 代码页即可，或用 Git Bash 看。

**关于包里的 `.git`**：`.git` 是完整的，可以直接 `git status` / `git diff` /
`git apply`。解压后 `git status` 会多显示一条：

```
 T app/src/main/assets/licenses/usbmountr.txt
```

这是**正常的**。原仓库里该文件是个指向 `LICENSE` 的符号链接，
而 zip 无法承载符号链接，打包时已按普通文件解引用（内容就是 LICENSE 正文，
对构建更友好）。**不要对它执行 `git checkout`**——Windows 默认
`core.symlinks=false`，checkout 会把这个文件写成一串路径文本
`../../../../../LICENSE`，反而把授权页内容弄坏。

另外这个仓库是**浅克隆**（`.git/shallow` 存在，只有 1 个提交，
HEAD = 上游 `v2.3.1-beta`）。`git log` 看不到历史、`git pull` 需要先
`git fetch --unshallow`，但 `git diff` / `git stash` / `git apply` 都正常，
不影响调试。

---

## 二、环境准备

### 2.1 必装

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **17 或 21** | AGP 8.5.0 最低 JDK 17；装 Android Studio 的话直接用自带 JBR 也行 |
| Android SDK | platform **34** + build-tools **34.0.0** | `compileSdk 34` |
| Gradle | 不用另装 | wrapper 已锁定 8.7，首次构建自动下载 |
| adb | 任意新版本 | 用 SDK 里的 `platform-tools` 即可 |
| Git for Windows | 可选 | 要用 `git apply` 打补丁、或看 diff 时需要 |

### 2.2 环境变量

```powershell
# 按实际路径修改
setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
setx PATH "$env:PATH;$env:LOCALAPPDATA\Android\Sdk\platform-tools"
```

设完**重开一个终端**才生效。

### 2.3 `local.properties`（Windows 上最容易翻车的一步）

在 `PhoneStick\local.properties` 新建（**这个文件不在包内，必须自己建**）：

```properties
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

要点：

- **正斜杠 `/` 最稳**，不用转义。用反斜杠必须写双份 `C\:\\Users\\...`，漏一个就报
  `The sdk.dir path is not valid`。
- 路径里有空格**不需要**引号。
- 这个文件已被 `.gitignore` 排除，不会污染仓库。
- 如果偷懒不建，Gradle 会去读 `ANDROID_HOME`，两者有一个就行。

---

## 三、构建

```powershell
cd PhoneStick

# 首次构建会拉 Gradle 8.7 + AGP + Kotlin 插件，约 3~8 分钟
.\gradlew.bat assembleRelease

# 产物
# app\build\outputs\apk\release\app-release.apk
```

调试包（不签名、可断点）：

```powershell
.\gradlew.bat assembleDebug
```

### 依赖拉取说明

`app/build.gradle` 里有一条 JitPack 依赖：

```gradle
implementation 'com.github.topjohnwu.libsu:core:6.0.0'   // Root 权限库
```

根 `build.gradle` 已配 `maven { url 'https://jitpack.io' }`。
**首次构建必须能访问 jitpack.io**，国内网络可能很慢或超时，表现为：

```
Could not resolve com.github.topjohnwu.libsu:core:6.0.0
```

对策（按优先级）：

1. 挂代理，或给 Gradle 配 `gradle.properties` 里的 HTTP 代理：
   ```properties
   systemProp.https.proxyHost=127.0.0.1
   systemProp.https.proxyPort=7890
   ```
2. 反复重试——JitPack 首次构建某个 commit 时会现场编译，第二次就快了。
3. 在 Linux 侧已构建成功过的话，把 `~/.gradle/caches/modules-2/files-2.1/com.github.topjohnwu.libsu`
   整个目录拷到 Windows 的 `C:\Users\<你>\.gradle\caches\modules-2\files-2.1\com.github.topjohnwu.libsu`，可离线构建。

> 之前是在 Debian 容器里构建的（`sdkbuild/Dockerfile`，openjdk-21 + cmdline-tools）。
> Windows 上不需要 Docker，直接 `gradlew.bat` 更省事。

---

## 四、签名与安装

### 4.1 签名配置（已内置，不用改）

`app/build.gradle` 里写死了：

```gradle
signingConfigs {
    release {
        storeFile file('release.jks')
        storePassword 'phonestickpass'
        keyAlias 'phonestick'
        keyPassword 'phonestickpass'
    }
}
```

`app/release.jks` 随包提供，SHA-256 指纹 `0c7df753...`。

### 4.2 装不上的处理（必读）

如果手机上装的是**官方签名的 2.3.0**，直接 `adb install -r` 会失败，
而且失败提示经常是空的：

```
adb: failed to install app-release.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]
```

或干脆没有输出。原因是签名不一致。**必须先卸载再装**：

```powershell
adb uninstall mingww64.phonestick
adb install "交付\PhoneStick-2.3.1-fixed.apk"
```

卸载会清掉 App 的 SharedPreferences（里面存着上次选中的镜像路径），
装好后重新在界面里选一次镜像即可，镜像文件本身不受影响。

### 4.3 装机后必做

1. 打开 App，Magisk 弹窗里授予 **Root 权限**（永久）。
2. 确认 Root 正常：
   ```powershell
   adb shell su -c "id"
   # uid=0(root) ... context=u:r:magisk:s0
   ```

> 如果 `adb shell su` 没弹窗也没反应，去 Magisk App → 超级用户 里手动放行，
> 或把「Shell」这个条目的策略改成允许。

---

## 五、验证流程（判读主机是否真的看到盘）

App 里点 `Mount USB Drive` 之后，**光看 App 提示不算成功**，要在电脑侧核对。

### 5.1 一条命令搞定

```powershell
.\tools\mount-and-verify.ps1 -Image "qqq.img"
```

脚本会：等 App 挂载 → 轮询主机是否出现新磁盘 → 打印容量/分区表 → 报告结论。

### 5.2 手工核对（三步）

```powershell
# 1. gadget 配置里有没有 mass_storage 链接
adb shell su -c "ls -l /config/usb_gadget/g1/configs/b.1/"

# 2. LUN 指向对不对
adb shell su -c "cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"

# 3. 主机侧有没有盘
Get-Disk | Where-Object BusType -eq 'USB'
```

**判读表**：

| 现象 | 含义 | 处理 |
|---|---|---|
| `configs/b.1/` 只有 `function0 -> ffs.adb` | gadget 没建 mass_storage 链接，主机只会看到 MTP 设备 | 见 5.3 |
| 出现了 `function* -> mass_storage.0` | 配置正常 | 看下一条 |
| LUN `file` 为空 | 镜像没绑进去 | 重新点挂载 |
| LUN `file` 路径不存在（如 `/media/0/...`） | 旧版脏数据 | 见 6.3 |
| `Get-Disk` 有盘但显示 **0 B** | 主机已枚举、等手机端 LUN 就绪 | 等 10 秒会自动刷新成真实容量 |
| `Get-Disk` 完全没有新盘，设备管理器里 MS 接口却显示成 **Android Composite ADB Interface** | OEM ADB 驱动按硬件 ID 抢占了 mass-storage 接口（**Windows 特有**） | 跑 `tools\fix-windows-usb-driver.ps1`，见 5.5 |
| `Get-Disk` 完全没有新盘 | USB 链路或配置问题 | 见 5.3 / 6.1 |

### 5.3 配置缺链接时（已知高发问题）

```powershell
adb shell su -c "setprop sys.usb.config mass_storage,adb"
# adb 会断开 3~5 秒，正常
adb shell su -c "ls -l /config/usb_gadget/g1/configs/b.1/"
# 应出现 function* -> .../mass_storage.0
```

恢复：

```powershell
adb shell su -c "echo '' > /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"
adb shell su -c "setprop sys.usb.config adb"
```

**为什么必须用 setprop 而不能直接 mkdir**：`configs/b.1/` 在 Android 17 上属主
`system:usb`、mode `drwxrwx---`，Magisk 域不在 `usb` 组，`mkdir` 返回 `EPERM`，
而且这条拒绝被 `dontaudit` 吞掉，dmesg 里连记录都没有——看起来像"莫名其妙失败"。
`setprop` 走的是系统 usb init，它由 `system` 身份重建整份 gadget，才建得出链接。
详见 `排查报告.md` 第十节。

### 5.4 端到端验收（确认真的可读可写）

```powershell
# 找到盘符后（假设是 E:）
"hello" | Out-File E:\test.txt
type E:\test.txt
```

再回手机侧确认确实落到镜像里：

```powershell
adb shell su -c "mount -t vfat -o ro /dev/block/loop0 /mnt/x 2>/dev/null; ls /mnt/x"
```

> 这一步在 Linux 侧已经完整跑通过（三次 md5 交叉一致），Windows 上重跑一遍即可。

### 5.5 Windows 专属：主机完全没有盘，MS 接口被认成了 ADB 设备

**症状**：App 显示挂载成功，手机端 `lun.0/file` 有值、`configs/b.1/` 里
`mass_storage.0` 链接也在，但 Windows 的 `Get-Disk` / 磁盘管理里什么都没有；
Linux / macOS 上同一个镜像却正常出盘。

**判据**（一条命令）：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\diagnose-windows-usb.ps1
```

如果它报：

```
USB\VID_05C6&PID_9015&MI_01\... -> service=WinUSB  inf=oem213.inf [OPLUS, Inc.]
```

那就是 **OEM 的 ADB 驱动包按硬件 ID `USB\VID_05C6&PID_9015&MI_01` 抢走了
mass-storage 接口**（该接口的类/子类/协议是 08/06/50）。
Windows 的匹配顺序是“硬件 ID 优先于接口类”，所以收件箱的 `usbstor.inf`
永远轮不到；Linux/macOS 按接口类匹配，因此不受影响。

**修复**（需要管理员，会自动备份驱动包）：

```powershell
.\tools\fix-windows-usb-driver.ps1
# 撤销
.\tools\fix-windows-usb-driver.ps1 -Restore
```

本质就三步：`pnputil /export-driver` → `pnputil /delete-driver ... /uninstall /force`
→ `pnputil /scan-devices`。修完后 MI_01 变成
`Service=USBSTOR / DriverInfPath=usbstor.inf`，盘符立刻出现，adb 不受影响
（MI_00 走的是另一个 INF）。

**为什么不能靠 App 绕开**：这台机器上 `sys.usb.config mass_storage`（单 MS）
被 gadget HAL 忽略、`idProduct` 写入被 SELinux 拒绝、App 自带的 `swy` gadget
在 Android 17 上 `ln -s` 报 `Operation not permitted`。详见 `排查报告.md` 第十五节。

---

## 六、已知的坑（Windows 上会额外遇到）

### 6.1 adb 反复掉线

**本机实测过**：主机 dmesg 持续刷 `USB disconnect` / `new high-speed USB device`，
换 20 秒掉一次。这不是软件问题，是线材/接口问题。

- 换一根**数据线**（很多线只有充电功能）。
- 插**主板后置 USB 口**，别用前面板、别用 Hub。
- 关掉 USB 选择性暂停：设备管理器 → USB 根集线器 → 电源管理 → 取消勾选"允许计算机关闭此设备"。
- 排查期间 `adb shell` 尽量用一条命令，别开长连接。

### 6.2 内存不足导致 OOM

设备总内存 11.4GB，但实测可用常年在 **196~446MB**。创建/导入大镜像时：

- 一次性 `fallocate` 或整块 `dd` 会触发 OOM，表现为 adb 掉线、App 被系统杀。
- 2.3.1 已改为 **16MB 分块 `dd` + `sync`**，缓解但没根治。
- 排查时**用 256MB~1GB 的小镜像**，不要一上来就 8GB。

```powershell
adb shell su -c "free -m; cat /proc/meminfo | head -3"
```

### 6.3 旧版留下的脏路径

旧版本可能把 `/media/0/PhoneStick/xxx.img`（丢了 `/data` 前缀，该目录根本不存在）
写进 SharedPreferences。内核不报错，但 LUN 指向空气，主机看到 0B 或干脆无盘。

2.3.1 的 `ImageStore.toRawPath()` 已做归一化（`/media/0/` → `/data/media/0/`）。
若在 Windows 上仍遇到，直接改偏好设置或重装：

```powershell
adb shell su -c "cat /data/data/mingww64.phonestick/shared_prefs/phonestick.xml"
```

### 6.4 CRLF 破坏 shell 脚本（Windows 特有，必看）

`tools/*.sh` 是给**手机**跑的。如果用记事本/VSCode 保存成 CRLF，
推到手机上执行会报一堆莫名其妙的错误：

```
sh: /data/local/tmp/x.sh: not found
: invalid option
```

或直接 `\r: not found`。

**保证 LF 的三种办法**：

1. `git config --global core.autocrlf input`（推荐，`.gitattributes` 已声明 `*.sh text eol=lf`）。
2. `adb push` 前在 VSCode 右下角把行尾切回 `LF`。
3. 推送时顺手转一次：
   ```powershell
   adb shell "sed -i 's/\r$//' /data/local/tmp/x.sh"
   ```

**Python 脚本不受影响**——`phonestick_parts.py` 写出的是二进制文件，
用的是 `open(..., 'wb')`，行尾转换不会碰它。只有 shell 脚本才怕 CRLF。

### 6.5 PowerShell 引号地狱

`adb shell su -c "..."` 在 PowerShell 里嵌套引号很容易被吃掉。实用写法：

```powershell
# 不要这样（会被 PowerShell 预解析）
adb shell su -c "if [ -f /x ]; then echo yes; fi"

# 改成把命令写进脚本推上去跑
adb push .\tools\device-doctor.sh /data/local/tmp/
adb shell su -c "sh /data/local/tmp/device-doctor.sh"
```

`device-doctor.sh` 已经把最常用的检查都包好了，直接跑它最省事。

**还有一个更隐蔽的坑**：`adb shell su -c "cmd1; cmd2; cmd3"` 里，分号后面的命令
经常**不是在 root 域里执行的**。实测：

```powershell
adb shell su -c "id; echo ---; cat /config/usb_gadget/g1/idProduct"
# uid=0(root) gid=0(root) context=u:r:magisk:s0     ← 第一条是 root
# cat: /config/usb_gadget/g1/idProduct: Permission denied
#                                                    ← 这条其实是 u:r:shell:s0 跑的
```

dmesg 里对应的是 `scontext=u:r:shell:s0` 的 avc denied，而不是 magisk 的拒绝，
很容易误判成"SELinux 不让 root 读 configfs"。**结论：别用分号串多条 root 命令**，
要么每条单独 `su -c`，要么把整段写成脚本推上去跑。

---

## 七、剩余待排查的 bug（迁移到 Windows 后的工作清单）

按优先级排序。**每条都写了怎么复现、怎么判断、往哪看**。

### P1 — 挂载后主机看不到盘（已知高发，但不稳定复现）

- **现象**：App 提示挂载成功，`lsblk`/`Get-Disk` 里没有新盘，或只看到 MTP 设备。
- **复现**：App 点 Mount → 立刻在电脑侧看 `configs/b.1/` 是否有 `mass_storage.0` 链接。
- **判断依据**：配置缺链接 = 配置层问题（见 5.3）；链接在但无盘 = UDC/枚举问题。
- **2026-10-26 更新**：Windows 上"链接在但完全无盘"已经定位为**确定性的驱动抢占**
  （OEM ADB 驱动按硬件 ID 认领 `MI_01`），不是时序问题，见 5.5。
  下面这段"轮询太短"的猜想只适用于 Linux 侧偶发；在 Windows 上不要再往这个方向查。
- **可疑代码**：`UsbGadgetController.mountImage()` 的 Strategy 1。
  它 `setprop sys.usb.config mass_storage,adb` 后**轮询 5 秒**等 init 建 LUN，
  但 init 重建 gadget 期间 adb 会断，Magisk 的 su shell 可能来不及返回，
  导致轮询提前结束、误判失败并落到 Strategy 2（自建 `swy` gadget）。
  可以试着把轮询从 5 秒加到 15 秒，或改成先 `sleep 3` 再开始轮询。

### P2 — UI 显示"Mounted"但主机实际没盘（状态不一致）

- **现象**：App 主界面显示已挂载，Disconnect 按钮可点，但电脑侧没有盘。
- **根因**：`getMountStatus()` 只检查 `lun.0/file` 非空就返回 `isMounted = true`，
  **没有校验 `configs/b.1/` 里是否存在 mass_storage 链接**。
  一旦有人（或某次 unmount）把 `sys.usb.config` 改回 `adb`，链接消失，
  但 `lun.0/file` 里的路径还在 → 永久误报已挂载。
- **修法**：`getMountStatus()` 增加链接存在性检查，
  同时读 `getprop sys.usb.config` 判断是否包含 `mass_storage`。
- **文件**：`UsbGadgetController.kt` ← `getMountStatus()`

### P3 — App 内「Create Blank Image」+ 内置格式化 GUI 全流程未实测

- **现状**：底层 shell 逻辑已单独验证通过（动态 MBR + loop 格式化 + 双重校验），
  但**从 App 界面上点完整流程没有走完**（上一轮因点击坐标换算 + 设备掉线中断）。
- **要验什么**：
  1. 创建 256MB 镜像 → 列表显示 `256 MB`（不是 0B）
  2. 主界面能选中、Mount 按钮高亮
  3. 挂载后电脑侧 `fdisk -l` 能看到 `Disklabel type: dos` 和一个分区
  4. 换 FAT32 / exFAT / ext4 各试一次
- **往哪看**：`ImageCreator.kt` 的 `formatImageNow()` / `verifyPartition()`，
  `adb logcat -s ImageCreator` 能看到每步的 rc 和校验结果。

### P4 — SAF 导入（content:// URI）未在真机点完

- **现状**：`ImageChooserActivity.handleImportedUri()` 已改为统一复制到
  `/data/media/0/PhoneStick`，但没从系统文件选择器实际点过一次。
- **要验什么**：从"下载"目录选一个 .img 导入 → 复制成功 → 自动回传主界面选中。
- **已知风险**：`UriPathResolver` 的 SAF 回退路径对有 `content://` 但无真实路径的
  provider（如网盘 App）可能拿不到路径，会走 `copyUriToAppCache`。

### P5 — 大镜像（>2GB）创建/导入的 OOM 行为

- **现状**：已改 16MB 分块 dd，但只测到 2GB，且是在内存剩余 400MB 时测的。
- **要验什么**：在内存更紧张时创建 4GB/8GB，看是否会 OOM 或触发 adb 掉线。
- **兜底**：`ImageCreator.createBlankImage()` 可改成用 `fallocate`（快但占页缓存）
  或 `truncate`（稀疏文件，最快，但某些 fs 上 loop 挂载会有空洞读问题）。

### P6 — 卸载后 USB 配置恢复不完全

- **现象**：卸载后 `sys.usb.config` 应回到挂载前的值。若挂载前是 `mtp,adb`，
  恢复时应还原成 `mtp,adb` 而不是简单的 `adb`。
- **可疑代码**：`restoreOriginalUsbConfig()` 逻辑看起来正常（优先用 prefs 里的原值），
  但如果 App 在中途被杀，`orig_usb_config` 可能没写进去。
- **验证**：
  ```powershell
  adb shell su -c "grep orig_usb_config /data/data/mingww64.phonestick/shared_prefs/phonestick.xml"
  ```

### 非 bug 但值得确认

- **主机侧时序**：挂载瞬间主机先出现 `Media removed, stopped polling` + 0B 设备，
  约 10 秒后才 `detected capacity change from 0 to N`。**这是正常的**，
  不要误判成 bug。
- **`nofua=1` 不持久化**：每次挂载都得重设，两个 Strategy 里都已写。

---

## 八、常用命令速查（PowerShell）

```powershell
# --- 构建 ---
cd PhoneStick
.\gradlew.bat assembleRelease
.\gradlew.bat clean assembleDebug

# --- 安装 ---
adb uninstall mingww64.phonestick          # 签名不一致时必须先卸
adb install "交付\PhoneStick-2.3.1-fixed.apk"

# --- 设备体检 ---
adb push .\tools\device-doctor.sh /data/local/tmp/
adb shell su -c "sh /data/local/tmp/device-doctor.sh"

# --- 看镜像 ---
adb shell su -c "ls -l /data/media/0/PhoneStick/"

# --- 看 LUN ---
adb shell su -c "cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"

# --- 看 gadget 配置 ---
adb shell su -c "ls -l /config/usb_gadget/g1/configs/b.1/"

# --- 看 App 日志 ---
adb logcat -s UsbGadgetController ImageCreator ImageStore

# --- 看内核错误 ---
adb shell su -c "dmesg | grep -iE 'I/O error|Medium Error|avc.*denied|truncated'"

# --- 主机侧看盘 ---
Get-Disk | Where-Object BusType -eq 'USB' | Format-Table Number,FriendlyName,Size

# --- 主机侧诊断 / 修复（Windows 驱动冲突）---
powershell -ExecutionPolicy Bypass -File .\tools\diagnose-windows-usb.ps1
.\tools\fix-windows-usb-driver.ps1            # 需管理员，自动备份
.\tools\fix-windows-usb-driver.ps1 -Restore   # 还原驱动

# --- 暴力恢复 ---
adb shell su -c "echo '' > /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"
adb shell su -c "setprop sys.usb.config adb"
```

---

## 九、故障对照表

| 报错 / 现象 | 原因 | 处理 |
|---|---|---|
| `The sdk.dir path is not valid` | `local.properties` 路径格式错 | 用正斜杠 `C:/Users/...` |
| `Could not resolve ... libsu` | JitPack 访问不了 | 挂代理 / 拷 Gradle 缓存 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 先 `adb uninstall` |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 装的比现有版本低 | 确认装的是 2.3.1 (versionCode 13) |
| `adb: no devices/emulators found` | 线/口问题、或 gadget 正在重建 | 等 5 秒重连；换线换口 |
| 主机 `fdisk -l` 显示 0 字节 | 主机已枚举但 LUN 未就绪 | 等 10 秒；查 LUN `file` 是否为空 |
| 主机完全无新磁盘 | `configs/b.1/` 缺 mass_storage 链接 | `setprop sys.usb.config mass_storage,adb` |
| 主机完全无新磁盘，但 MS 接口显示为 “Android Composite ADB Interface” | OEM ADB 驱动按硬件 ID 抢占 MS 接口 | `tools\fix-windows-usb-driver.ps1`（5.5 节） |
| `fdisk w` 报 `fsync device failed` | 镜像不在 `media_rw_data_file` 位置 | 移到 `/data/media/0/PhoneStick/` |
| 分区存在但容量为 0 / 写入丢失 | MBR 分区扇区数 > 镜像实际容量 | 用 2.3.1 的动态计算，别用旧模板 |
| App 显示镜像 0B | 用 Java `File.length()` 读 FUSE 路径失败 | 2.3.1 已改为 shell `stat` |
| `sh: ...: not found` | 脚本被存成 CRLF | 转 LF 重推（见 6.4） |
| App 创建镜像"成功"但盘是空的 | 格式化候选全失败仍返回 true | 2.3.1 已改为失败即 false |

---

## 十、如果要从头复现改动

```powershell
cd PhoneStick
git stash            # 或者 git checkout . 丢弃现有改动
git apply ..\交付\源码改动.patch
git status           # 应看到 7 个 M + ImageStore.kt 未跟踪
```

补丁基于上游 v2.3.0（commit `HEAD`）生成，共 `7 files changed, 614 insertions(+), 262 deletions(-)`，
另新增 1 个文件。
