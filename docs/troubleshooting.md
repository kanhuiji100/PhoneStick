# PhoneStick 分区/格式化失败排查报告

> **文档状态**：这是 2.3.0 阶段（versionCode 12）的根因排查记录。
> 其中的结论（SELinux 写权限、MBR 扇区数、Windows 驱动抢占）至今有效，
> 但项目已迭代到 **2.3.7**，现状请以 `README.md`（仓库根） 与 `handoff.md` 为准。
> 后续新增内容见 `design.md`（稀疏镜像 / 空间回收 / 相关 bug 复盘）。

- 设备：Redmi K20 Pro (raphael)，Android 17 (SDK 37)，kernel 4.14.357
- Root：Magisk 31.1
- PhoneStick 版本：2.3.0 (`mingww64.phonestick`, versionCode 12)
- 排查时间：2026-10-26

## 一、结论

核心根因是 **SELinux 拦截了 USB gadget 写线程对镜像文件的写入**，不是内核不支持、也不是 fsync 问题（初判有误，已实测修正）。

| 现象 | 真实原因 |
|---|---|
| `fdisk w` 报 `fsync device failed: 输入/输出错误` | SELinux 拒绝 `u:r:kernel:s0` 写 `app_data_file` |
| App 内置格式化“成功”但盘是空的 | `newfs_msdos` 只认块设备；设备无 `mkfs.vfat`，且失败被静默吞掉 |
| 电脑看到盘但无分区 | 镜像里从来没有 MBR，格式化只在偏移 0 铺了裸文件系统 |

## 二、核心根因：SELinux 阻止 gadget 写入

### 证据

手机端 dmesg：

```
avc: denied { write } for comm="file-storage"
  path="/data/data/mingww64.phonestick/files/phonestick_drive.img"
  scontext=u:r:kernel:s0
  tcontext=u:object_r:app_data_file:s0
  tclass=file permissive=0
```

主机端对应：

```
sd 4:0:0:0: [sdb] Sense Key : Medium Error [current]
sd 4:0:0:0: [sdb] Add. Sense: Write error - auto reallocation failed
I/O error, dev sdb, sector 2048 op 0x1:(WRITE)
```

`comm="file-storage"` 是内核的 f_mass_storage 线程，域为 `u:r:kernel:s0`。
PhoneStick 默认把镜像放在 `/data/data/mingww64.phonestick/files/`，标签是
`app_data_file`，而 Android 的 SELinux 策略不允许 kernel 域写这个标签。

**读权限是放行的**，所以出现“能认盘、能显示容量、能列出分区，一写就挂”的典型症状。

### 对照实验

同一台机器、同一个镜像内容、同样的 `dd` 命令，只改存放位置：

| 目录 | SELinux 标签 | kernel 可写 | kernel 可读 |
|---|---|---|---|
| `/data/data/mingww64.phonestick/files/` | `app_data_file` | **否** | 是 |
| `/data/local/tmp/` | `shell_data_file` | 是 | **否** |
| `/data/media/0/` | `media_rw_data_file` | **是** | **是** |

实测数据：

- `app_data_file` → `Medium Error / Write error - auto reallocation failed`
- `shell_data_file` → 写入成功，但读取时报 I/O error，`fdisk: cannot open /dev/sdb`
- `media_rw_data_file` → **读写都正常，dmesg 零 avc 拒绝**

`/data/local/tmp` 那个坑特别容易踩：换过去发现能写了，但马上变成读不了。

### 修复

把镜像移到 `/data/media/0/PhoneStick/` 即可。这一条就解决了 `fdisk w` 的问题。

```bash
mkdir -p /data/media/0/PhoneStick
cp /data/data/mingww64.phonestick/files/phonestick_drive.img \
   /data/media/0/PhoneStick/
chown 1023:1023 /data/media/0/PhoneStick/phonestick_drive.img
```

然后让 LUN 指向新路径（注意：解绑 UDC 会短暂掐断 adb）。

## 三、次要问题一：App 内置格式化静默失败

`ImageCreator.kt` 的 FAT32 候选命令：

```kotlin
"newfs_msdos -F 32 -c 1 '$escapedPath'",        // 候选 1
"LOOP=$(losetup -f --show '$escapedPath') && newfs_msdos -F 32 -c 1 $LOOP && ...",  // 候选 2
"mkfs.vfat -F 32 -I '$escapedPath'",            // 3 — 不存在
"mkfs.fat -F 32 -I '$escapedPath'",             // 4 — 不存在
"mkfs.vfat '$escapedPath'",                     // 5 — 不存在
"toybox mkfs.vfat '$escapedPath'",              // 6 — 不存在
"busybox mkfs.vfat '$escapedPath'",             // 7 — 不存在
```

实测：

```
$ newfs_msdos -F 32 -c 1 /data/.../phonestick_drive.img
newfs_msdos: ioctl(BLKSSZGET) for bytes/sector failed: Inappropriate ioctl for device
exit=1

$ mkfs.vfat ...
/system/bin/sh: mkfs.vfat: inaccessible or not found    (exit 127)
```

设备上只有 `newfs_msdos`、`mke2fs`（`mkfs.ext4` 软链）、`mkfs.exfat`、`mkfs.erofs`、`mkfs.ntfs`，
**没有 `mkfs.vfat` / `mkfs.fat`**。安卓的 `newfs_msdos` 只接受块设备，普通文件传进去必然失败。

但 `createBlankImage()` 在全部候选失败时：

```kotlin
if (!formatSuccess && candidates.isNotEmpty()) {
    formatMsg = " (Warning: mkfs $fmt failed: $lastError)."   // 只拼进提示
}
onComplete(true, "Successfully created image ...", outputFile)  // 仍然 true
```

实测产出的 2GB 镜像前 512 字节全 `00`，连 `55 AA` 都没有。

## 四、次要问题二：MBR 分区扇区数必须与镜像实际大小一致（真正的元凶）

这是本次排查最关键的一条，也是第 3 轮修源码时才彻底定位的。

### 错误结论（已推翻）

排查初期发现 `dd` 到 `loopNp1` 报"512 bytes copied"但读回全零，一度归因为
"f2fs 上 loop 分区写入会静默丢数据，必须按字节偏移直接写"。**这个结论是错的。**

### 真实原因

内核日志里其实一直有明确提示，只是被淹没了：

```
loop47: p1
loop47: p1 size 4192256 extends beyond EOD, truncated
```

`ps_parts.mbr` 里的分区项是**按 2GB 镜像写死**的：

```
分区项: 00 fe ff ff | 0c | fe ff ff | 00 08 00 00 | 00 f8 3f 00
                                       起始 LBA=2048  扇区数=4192256 (=2047 MB)
```

一旦镜像不是 2GB（比如 256MB 只有 524288 扇区），分区表声明的 4192256 扇区
远超实际容量，内核直接 **truncate 成 0 长度分区**。此时：

- `/dev/block/loopNp1` 节点**照样存在**，`[ -b ]` 判断为真
- `newfs_msdos` 返回 **0**，还打印了正常的 BPB 参数
- 但**所有写入都被内核丢弃**，镜像文件里一个字节都不会变

于是出现"格式化成功但盘是空的"这个诡异现象。

### 对照实验

| 镜像大小 | 旧 MBR（写死 4192256） | 动态 MBR（按实际算出） |
|---|---|---|
| 128 MB | 写入全丢，`@1048658` = `0000...` | 正常，`@1048658` = `FAT32   ` |
| 256 MB | 写入全丢 | 正常 |
| 512 MB | 写入全丢 | 正常 |
| 2048 MB | **恰好匹配，能成功** | 正常 |

这解释了为什么之前"2GB 镜像能格式化成功"——不是方法对了，只是尺寸恰好撞上。

### 附带的 mksh 坑

如果手工验证，`od -An -c` 会把 `\0` 输出成真 NUL 字节，被 `$(...)` 截断，
导致读回来的值永远是空/零，看起来像"没写进去"。**必须用 `od -An -tx1 -v`**。

### 正确的做法

```bash
SZ=$(stat -c %s "$IMG")
TOTAL=$(( SZ / 512 ))
PART=$(( TOTAL - 2048 ))          # 起始 LBA 2048，剩余全部给分区 1
```

再把 `PART` 按小端写进偏移 446 的分区项。Kotlin 侧对应 `writeMbr()` 里的
`le32(partSectors)`。

另外加一道保险：格式化前用 `blockdev --getsize64 ${LOOP}p1` 复查分区长度，
为 0 就直接报错，不给"假成功"留空间。

## 五、完整修复流程（已端到端验证）

### 第 1 步：生成分区头数据（电脑上）

```bash
python3 tools/phonestick_parts.py 2147483648 /tmp/ps_parts
```

产出 `ps_parts.mbr`（512B）和 `ps_parts.tail`（约 32MB）。

> 为什么不在手机上拼：Android mksh 有两个坑——
> `printf '\x00'` 会产生真 NUL 被 `$(...)` 截断，触发 `set -e` 退出；
> 且 mksh 算术是 32 位，`2147483648/512` 会溢出成 `-4194304`。

### 第 2 步：推送到手机并执行

```bash
adb push /tmp/ps_parts.mbr  /data/local/tmp/
adb push /tmp/ps_parts.tail /data/local/tmp/
adb push tools/phonestick_fix.sh  /data/local/tmp/psfix.sh

# 必须后台跑：解绑 UDC 会掐断 adb，前台执行会被杀
adb shell "su -c 'nohup sh /data/local/tmp/psfix.sh \
  /data/data/mingww64.phonestick/files/phonestick_drive.img \
  > /data/local/tmp/psfix.log 2>&1 &'"

sleep 10
adb shell 'su -c "cat /data/local/tmp/psfix.log"'
```

实测输出：

```
[phonestick] 复制到 /data/media/0/PhoneStick/phonestick_drive.img
[phonestick] 已移除原位置文件
[phonestick] 目标标签: u:object_r:media_rw_data_file:s0
[phonestick] 镜像大小: 2147483648 字节
[phonestick] MBR 签名: 55aa   分区引导签名: 55aa
[phonestick] LUN 已绑定: /data/media/0/PhoneStick/phonestick_drive.img
[phonestick] 完成。现在可以在电脑上执行 fdisk /dev/sdX 正常分区了。
```

脚本会做三件事：搬到 `media_rw_data_file` 位置、写入 MBR + FAT32 分区头、重绑 LUN 并设 `nofua=1`。

### 第 3 步：在电脑上正常分区

```bash
sudo fdisk /dev/sdb
#   d       删除占位分区
#   n → p → 1 → 回车 → 回车
#   t → c   类型改为 W95 FAT32 (LBA)
#   w       写入

sudo partprobe /dev/sdb
sudo mkfs.vfat -F 32 -n PHONESTICK /dev/sdb1
```

### 验证结果

```
$ sudo fdisk -l /dev/sdb
Disklabel type: dos
Device     Boot Start     End Sectors Size Id Type
/dev/sdb1        2048 4194303 4192256   2G  c W95 FAT32 (LBA)

$ sudo fsck.vfat -n /dev/sdb1
/dev/sdb1: 1 files, 1/523005 clusters          ← 无错误

$ sudo mount /dev/sdb1 /tmp/final && df -h /tmp/final
/dev/sdb1       2.0G  8.0K  2.0G   1% /tmp/final  ← 2GB 容量正常
```

dmesg 中 `Medium Error` / `I/O error` 计数：**0**。

## 六、关于 nofua

初始判断认为是 `nofua=0` 导致 fdatasync 失败，实测这是**次要因素**：
在 `app_data_file` 位置即使设了 `nofua=1`，SELinux 仍然拒绝写入，报的也是同样的 `Medium Error`。

不过 `nofua=1` 仍建议设置，可以避免不必要的同步开销：

```bash
echo 1 > /config/usb_gadget/g1/functions/mass_storage.0/lun.0/nofua
```

它**不会持久化**，每次挂载后都要重设。

## 七、给上游的建议（可提 issue）

1. **最优先：镜像不要放 `filesDir`**
   应该放到 `/data/media/0/`（或 app 外部存储目录）并确保标签是 `media_rw_data_file`。
   这是 `fdisk` 写分区表失败的直接原因，影响所有 Android 10+ 设备。

2. **格式化失败必须上报**
   `createBlankImage` 在 7 条候选全败时仍返回 `success=true`，用户完全无法察觉。

3. **`newfs_msdos` 统一走块设备**
   候选 1 直接传文件必然失败，应改为先 `losetup` 再格式化，
   并把 `mkfs.exfat` 加入兜底（设备上有这个工具）。

4. **格式化后写 MBR**
   当前在偏移 0 直接铺 FAT32，等于"超级软盘"，主机端会认为整盘无分区。
   要支持分区必须先写 MBR/GPT。

5. **设置 `nofua=1`**
   减少不必要的同步，兼容性更好（低优先级）。

## 八、文件清单

| 文件 | 用途 |
|---|---|
| `tools/phonestick_parts.py` | 生成 MBR 与 FAT32 分区头，支持任意镜像大小（仅用于手动修复，App 内已动态计算） |
| `tools/phonestick_fix.sh` | 手机端修复脚本：搬移 + 写分区头 + 补配置链接 + 重绑 LUN |
| `tools/device-doctor.sh` | 设备一键体检 |
| `tools/mount-and-verify.ps1` | Windows 侧挂载并确认主机出盘 |
| `tools/cleanup-device.sh` | 清理 `/data/local/tmp` 调试残留 |
| `tools/switch_lun.sh` | 单纯切换 LUN 到指定镜像 |
| `PhoneStick/` | 已修复的源码工程，见下一节 |
| `sdkbuild/Dockerfile` | Linux 容器构建环境（Windows 上不需要） |

## 九、源码修复清单

所有改动都在 `PhoneStick/app/src/main/java/mingww64/phonestick/` 下。

| 文件 | 改动 |
|---|---|
| `ImageStore.kt`（新增） | 镜像元数据统一走 root shell 的 `stat`，不再依赖 Java `File`；定义 `/sdcard/PhoneStick` ↔ `/data/media/0/PhoneStick` 的路径映射 |
| `ImageCreator.kt` | MBR 分区扇区数按实际容量动态计算；格式化统一走 loop 分区；失败明确返回 `false`；创建改为 16MB 分块 dd 防 OOM；校验改为读原始字节 |
| `ImageChooserActivity.kt` | 列表改用 `ImageStore.listImages()`；导入统一复制到 `/sdcard/PhoneStick`；导入/创建成功后自动回传主界面 |
| `ImageFilesAdapter.kt` | 文件大小由 `sizeProvider` 从 shell 数据读取；重命名走 `mv -f` |
| `MainActivity.kt` | `refreshStatus` 全部在 IO 线程完成查询，UI 线程不再做 shell 调用；选中项不会因 `File.exists()` 误判被清空 |
| `UriPathResolver.kt` | SAF 回退复制的落点从 `filesDir` 改为 `/data/media/0/PhoneStick` |
| `UsbGadgetController.kt` | 路径归一化后再写 LUN；`chmod 0666`；空文件（size==0）直接拒绝挂载；两个 Strategy 都写 `nofua=1` |
| `build.gradle` | `versionCode 13` / `versionName 2.3.1` |

## 十、遗留问题：gadget 配置缺 mass_storage 链接（已找到解法）

挂载后若主机只识别出 MTP/PTP 设备（`lsusb` 显示 `18d1:4e11`）而不是 U 盘，
说明 gadget 的配置目录缺少 `mass_storage.0` 链接：

```
/config/usb_gadget/g1/configs/b.1/
  function0 -> ../../../../usb_gadget/g1/functions/ffs.adb   ← 只有 adb
  （缺 mass_storage.0 的链接）
```

按 ConfigFS 标准做法应在 `configs/b.1/` 下 `mkdir mass_storage.0`，
但该目录在 Android 17 上被 SELinux 锁死（属主 `system:usb`、mode `drwxrwx---`，
magisk 域既不在 `usb` 组、也没有对应 avc 规则，`mkdir` 直接返回 `EPERM`，
且因为被 `dontaudit` 吞掉，dmesg 里连拒绝记录都看不到）。

在同一时刻 `echo ... > lun.0/file` 却能成功（uid 0 写普通属性文件不受限），
所以「能挂载但主机看不到盘」不是 App 逻辑问题，而是**配置层缺链接**。

### 解法（已实测通过）

用系统属性触发 usb init 重建整份 gadget，它会同时创建 adb 与 mass_storage 链接：

```sh
su -c 'setprop sys.usb.config mass_storage,adb'
# adb 会断开数秒，重连后：
ls -l /config/usb_gadget/g1/configs/b.1/
#   function0 -> .../ffs.adb
#   function2 -> .../mass_storage.0   ← 出现了
```

然后写 LUN 即可正常出盘：

```sh
su -c 'echo "/data/media/0/PhoneStick/qqq.img" \
  > /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file'
```

恢复：`setprop sys.usb.config adb`（同时把 `lun.0/file` 写空）。

实测时主机 dmesg 出现「Media removed, stopped polling」→ 约 10 秒后
`detected capacity change from 0 to 2097152` → `sdb: sdb1`，
即主机先看到 0 容量、等手机端 LUN 就绪后才真正读到盘，这是正常时序，不是故障。

### 端到端验证结果（1GB 镜像 qqq.img）

| 环节 | 结果 |
|---|---|
| 主机 `fdisk -l /dev/sdb` | `1 GiB, 2097152 sectors`，`Disklabel type: dos`，`/dev/sdb1 2048..2097151 1023M 83 Linux` |
| 主机 `dd` 写 8MB + `conv=fsync` | 21.7 MB/s，**无 I/O error**（原报错点已消除） |
| 三次 md5 交叉比对 | 主机写入 = 主机回读 = 手机侧直接读镜像，全为 `d02bb9de...` |
| 主机 `mkfs.vfat -F 32 -n PSTEST /dev/sdb1` | 成功，桌面自动挂载为 `PSTEST` |
| 写文件到挂载点再读回 | `test.txt` 内容一致（含中文日期，编码无损） |
| 手机侧 loop 挂载镜像复查 | `mount -t vfat /dev/block/loop48` → 能看到同一个 `test.txt` |
| 手机 dmesg | 全程无 `I/O error`、无 `Medium Error`、无 `file-storage` 的 avc denied |

结论：**镜像 → LUN → 主机分区 → 主机格式化 → 主机挂载读写 → 落回镜像** 整条链路已闭环。

## 十一、构建与安装侧的问题（之前未记录）

这一节都是实测踩到的，和内核/SELinux 无关，但会直接卡住开发。

### 11.1 签名不一致 → `-r` 静默失败

官方 2.3.0 用的是上游作者自己的密钥；仓库自带的 `app/release.jks`
（SHA-256 `0c7df753...`）是另一套。两者指纹不同，`adb install -r` 会失败，
而且**经常连错误信息都不打印**：

```
$ adb install -r app-release.apk
（无输出，退出码非 0）
```

必须卸载重装：

```bash
adb uninstall mingww64.phonestick
adb install PhoneStick-2.3.1-fixed.apk
```

代价是 SharedPreferences 被清空（上次选中的镜像路径丢失），镜像文件本身不受影响。

### 11.2 卸载后 `pm install` 仍失败：目录属主问题

用 `adb push` 推到 `/data/local/tmp/inst/` 的 APK，属主是 **shell**，
app 进程读不了。绕过办法：

```bash
adb shell su -c "chmod 777 /data/local/tmp/inst"
adb shell su -c "pm install -r /data/local/tmp/inst/ps.apk"
```

### 11.3 构建环境：Debian trixie 没有 openjdk-17

原 `sdkbuild/Dockerfile` 写的是 `openjdk-17-jdk-headless`，
在 `debian:trixie-slim` 上直接报：

```
E: Unable to locate package openjdk-17-jdk-headless
```

trixie 只有 **openjdk-21**。改成 21 并显式设 `JAVA_HOME` 后构建通过：

```dockerfile
RUN apt-get install -y openjdk-21-jdk-headless ...
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

AGP 8.5.0 要求 JDK 17+，21 完全兼容；`compileOptions` 里的
`VERSION_17` 指的是**字节码目标**，不是 JDK 版本，不要混淆。

### 11.4 版本号改了但产物没变

第一次 `assembleRelease` 出来的 APK 仍是 `versionCode=12`，
原因是容器里残留了改版本号之前生成的 `app/build`。清理后重编才拿到 13：

```bash
rm -rf app/build/outputs app/build/kotlin
```

判断产物对不对，别只看文件名：

```bash
aapt dump badging app-release.apk | head -1
# package: name='mingww64.phonestick' versionCode='13' versionName='2.3.1'
```

## 十二、运行时环境干扰（容易被误判成 bug）

### 12.1 内存极低

设备总内存 11452 MB，但实测可用长期只有 **196~446 MB**。后果：

- 一次性 `fallocate` 或整块 `dd` 大镜像 → 触发 OOM → **adb 直接掉线**
- 表现为"挂载到一半断了"，很容易误判成 gadget 崩溃

2.3.1 的 `createBlankImage()` 已改为 **16 MB 分块 `dd` + `sync`**，
缓解但没有根治。排查期建议用 256 MB ~ 1 GB 的镜像。

```bash
adb shell su -c "free -m; grep MemAvailable /proc/meminfo"
```

### 12.2 USB 物理连接不稳定

本机实测主机 dmesg 持续刷：

```
usb 3-7: USB disconnect, device number 12
usb 3-7: new high-speed USB device number 13 using xhci_hcd
```

约 20 秒循环一次，`adb: no devices/emulators found` 随之反复出现。

**这不是软件问题。** 排查方向：

- 换一根**数据线**（很多线只有充电线芯，没有 D+/D-）
- 插**主板后置 USB 口**，不要用前面板、不要过 Hub
- 关闭 USB 选择性暂停

误判风险很高——掉线时如果正好在写 LUN 或写 MBR，看起来就像"写入失败"。

### 12.3 gadget 重建期间 adb 必然断开

`setprop sys.usb.config ...` 会让系统 usb init 重新枚举，
**adb 断 3~5 秒是正常行为**，不是异常。所以：

- 需要连续多步操作的脚本，必须 `nohup ... &` 后台跑并写日志，
  前台执行会被 SIGKILL 在半路
- 脚本里凡是"解绑 UDC / setprop usb.config"之后的操作，
  都要先 `sleep` 再继续

### 12.4 主机侧枚举时序

挂载瞬间，主机 dmesg 会出现：

```
sd 4:0:0:0: [sdb] Media removed, stopped polling
sd 4:0:0:0: [sdb] Attached SCSI removable disk
...（约 10 秒后）
sd 4:0:0:0: [sdb] 2097152 512-byte logical blocks: (1.07 GB/1.00 GiB)
sdb: detected capacity change from 0 to 2097152
 sdb: sdb1
```

**先出现 0 容量设备、10 秒后才变成真实容量，是正常的。**
不要在这个窗口期内下结论说"盘坏了"。

## 十三、剩余待排查问题

| # | 问题 | 状态 | 文件 |
|---|---|---|---|
| P1 | 挂载后主机偶发看不到盘 | 已知高发，不稳定复现 | `UsbGadgetController.mountImage()` Strategy 1 的 5 秒轮询可能太短 |
| P2 | UI 显示 Mounted 但主机实际没盘 | **确定存在** | `getMountStatus()` 只看 `lun.0/file` 非空，没校验配置链接是否存在 |
| P3 | App 内「Create Blank Image」GUI 全流程 | 未实测（底层 shell 已验证） | `ImageCreator.kt` |
| P4 | SAF `content://` 导入 | 未实测 | `ImageChooserActivity.handleImportedUri()` |
| P5 | >2GB 镜像创建/导入的 OOM 行为 | **已解决（2.3.2 起改为 truncate 稀疏创建，不写数据块）** | `ImageCreator.createBlankImage()` |
| P6 | 卸载后 USB 配置恢复不完全 | 待确认 | `restoreOriginalUsbConfig()` |
| P7 | Windows 主机完全看不到盘，Linux/macOS 正常 | **根因已定位并修复**（主机侧驱动抢占） | 见第十五节；`tools/fix-windows-usb-driver.ps1` |

P2 是最值得先修的：App 被外部改过 `sys.usb.config` 之后，
`lun.0/file` 里的路径还在，但配置链接已经消失，
于是界面永远显示"Mounted"，用户怎么点都没反应。

详细复现步骤与排查切入点见 **`windows.md` 第七节**。

## 十四、交付物与工具

| 文件 | 用途 |
|---|---|
| `windows.md` | Windows 环境搭建、构建、验证、剩余 bug 清单 |
| `handoff.md` | 当前进度、本机（Windows）改动、遗留待办、回 Linux 后的继续步骤 |
| `交付/PhoneStick-2.3.1-fixed.apk` | 已编译好的修复版，可直接安装 |
| `交付/源码改动.patch` | `git apply` 可复现全部改动 |
| `tools/device-doctor.sh` | 设备一键体检：root、配置链接、LUN、镜像标签、内核错误、内存、工具链 |
| `tools/mount-and-verify.ps1` | Windows 侧挂载并自动确认主机是否出盘 |
| `tools/cleanup-device.sh` | 清理 `/data/local/tmp` 下的调试残留，不动镜像 |
| `tools/phonestick_parts.py` | 手工生成 MBR + FAT32 分区头（任意容量） |
| `tools/phonestick_fix.sh` | 手工修复：搬移镜像 + 写分区表 + 补配置链接 + 重绑 LUN |

| `tools/diagnose-windows-usb.ps1` | **新增**：只读诊断——adb、LUN、配置链接、MS 接口绑定、盘符，逐项给出结论 |
| `tools/fix-windows-usb-driver.ps1` | **新增**：Windows 侧修复——找出抢占了 mass-storage 接口的 OEM ADB 驱动包，备份后移除，让 USBSTOR 接管；`-Restore` 可还原 |
| `tools/driver-backup/` | 上述脚本导出的驱动包备份（`android_winusb.inf` 等），还原用 |

所有 shell 脚本都用 LF 换行；仓库已加 `.gitattributes` 固定
`*.sh text eol=lf`，避免在 Windows 上被 `core.autocrlf` 转成 CRLF
后推到手机报 `not found`。

## 十五、Windows 主机看不到虚拟 U 盘：OEM ADB 驱动抢占 mass-storage 接口

> 2026-10-26 在本机（Windows + Redmi K20 Pro）实测定位并修复。
> 同一台手机、同一个镜像：Linux / macOS 正常出盘，只有 Windows 不出盘。

### 现象

- 手机端一切正常：`lun.0/file` 有值、UDC 为 `configured`、`configs/b.1/` 里
  `function0 -> ffs.adb`、`function2 -> mass_storage.0`。
- App 提示挂载成功。
- Windows：`Get-Disk` 没有新盘，磁盘管理里也没有；设备管理器里看不到
  “USB Mass Storage Device”或新的“磁盘驱动器”。
- Linux `fdisk -l` / macOS `diskutil list` 都能看到同一个盘。

### 根因：Windows 的驱动排名把 MS 接口判给了 ADB 驱动

`mass_storage,adb` 组合在主机侧看到的描述符是：

| 接口 | 类/子类/协议 | 含义 |
|---|---|---|
| MI_00 | ff/42/01 | ADB |
| MI_01 | **08/06/50** | USB 大容量存储（BOT） |
| 设备 | 05C6:9015 | 高通通用 Android VID/PID |

Windows 选驱动时**先按硬件 ID 匹配 INF，匹配不上才回落到接口类匹配**。
本机装过两个手机厂的 ADB 驱动包，其中 OPLUS 的 `oem213.inf` 有一条：

```
%CompositeAdbInterface% = USB_Install, USB\VID_05C6&PID_9015&MI_01 ;mtp,adb,mass_storage
```

它按硬件 ID 把 **MI_01 抢给 WinUSB（Android ADB 驱动）**，优先级高于
收件箱 `usbstor.inf` 的 `USB\Class_08&SubClass_06&Prot_50` 类匹配。
于是 MS 接口虽然挂在总线上，却被当成 ADB 接口打开，USBSTOR 从未 attach，
磁盘自然不出现。Linux/macOS 按接口类匹配（usb-storage /
IOUSBMassStorageClass），所以同一份描述符在那边完全正常。

实测证据：

```
USB\VID_05C6&PID_9015&MI_01\6&9D605ED&0&0001
  Service        = WinUSB
  DriverInfPath  = oem213.inf
  DriverProvider = OPLUS, Inc.
  CompatibleIds  = USB\Class_08&SubClass_06&Prot_50 | ...
USB\VID_05C6&PID_9015&MI_00\...  -> oem106.inf (vivo) / WinUSB / ADB   ← 这条是对的
```

即同一个复合设备的两个接口：MI_00 被 vivo 的 INF 正确认成 ADB，
MI_01 被 OPLUS 的 INF 错误认成 ADB。两份 INF 各只服务一个设备节点，
删掉 OPLUS 那份不影响 adb。

### 修复（Windows 侧，需要管理员）

```powershell
# 一键：备份 → 解绑 → 重扫 → 等盘出现
.\tools\fix-windows-usb-driver.ps1

# 撤销（万一别的设备要用这份驱动）
.\tools\fix-windows-usb-driver.ps1 -Restore
```

脚本实际执行：

```powershell
pnputil /export-driver oem213.inf .\tools\driver-backup
pnputil /delete-driver oem213.inf /uninstall /force
pnputil /scan-devices
```

删除后 Windows 重新排名，MI_01 落到收件箱驱动：

```
Service        = USBSTOR
DriverInfPath  = usbstor.inf
DriverProvider = Microsoft
```

实测结果：盘符 `E:`、`PSTEST`、FAT32、1021 MB；`adb devices` 仍然正常。
主机写入 `E:\ws-test.txt` 后，手机侧直接 `grep` 镜像原始字节能搜到
`PhoneStick Windows test 2026-10-26 12:00`，说明写入确实落进了镜像，
不是缓存假象。随后在手机侧强制 UDC 解绑/重绑做一次完整重新枚举，
盘符与 adb 都照常恢复——**修复是持久的**，因为抢接口的驱动包已经从
驱动仓库里删掉了，每次枚举都会重新排名。

> 校验手段备注：这次想在手机侧 `mount -t vfat` 回读镜像时，
> 无论整盘还是 `offset=1048576` 都报 `Invalid argument`（LUN 正被 gadget 占用）。
> 直接 `grep -a` 镜像原始字节反而稳定可靠，推荐用它做"写入是否落盘"的证据。

只读诊断（不需要管理员）：

```powershell
.\tools\diagnose-windows-usb.ps1
```

### 为什么 App 侧绕不开（这台机器上三条路都堵死）

1. **改用 `sys.usb.config mass_storage`（单 MS，按 rc 应该是 PID 0xF000）**
   实测切过去后主机侧仍是 05C6:9015 + adb + MI_01，没有出现 0xF000 设备，
   也不出盘。该 ROM 的 gadget HAL（`vendor.usb.use_gadget_hal=1`）没有按
   `init.qcom.usb.rc` 里 `configfs=1` 时代的 F000 组合执行。

   `/vendor/etc/init/hw/init.qcom.usb.rc` 里直接写死的两条（仅 legacy configfs=1 路径）：

   | `sys.usb.config` | idVendor:idProduct | mass_storage 链接到 |
   |---|---|---|
   | `mass_storage` | `05C6:F000` | `configs/b.1/f1` |
   | `mass_storage,adb` | `05C6:9015` | `configs/b.1/f2` |

   rc 里还有 mtp / diag / rndis 等一大堆组合，各有自己的 PID
   （如 `mtp,diag,adb` → `05C6:903A`），与本问题无关。

   注意 `05C6:F000` 的键在注册表里出现过（`Service=USBSTOR`），说明历史上
   确实有过 F000 出盘的记录；但当前 HAL 路径下复现不出来。

   另外一个容易误判的点：这台机器上 `sys.usb.state` **一直是空的**
   （挂载成功、UDC `configured` 时也为空），不要拿它判断挂载状态。
2. **改 `idProduct` 避开 OEM INF**
   `/config/usb_gadget/g1/idProduct` 对 magisk 域写操作直接
   `Permission denied`（SELinux），而且本来也必须 UDC 解绑后才能写。
3. **用 App 自带的 `swy` gadget（1d6b:0104）**
   Android 17 上创建配置链接失败：

   ```
   ln: cannot create symbolic link from 'functions/mass_storage.0'
       to 'configs/c.1/mass_storage.0': Operation not permitted
   ```

   也就是 `UsbGadgetController` 的 Strategy 2 在这台设备上根本挂不起来，
   需要重新设计或直接去掉（它目前只是 Strategy 1 失败后的兜底，
   而在这台机器上兜底也是坏的，容易误导排查）。

结论：这是 **Windows 驱动排名问题，不是 PhoneStick 的挂载逻辑问题**。
App 侧最多能做到“挂载后提示 Windows 用户跑一次修复脚本”，
真正的修复必须在主机侧完成。

### 通用判据（其他用户也能用）

设备管理器里如果有一个叫 **Android Composite ADB Interface** 的设备，
它的硬件 ID 是 `USB\VID_xxxx&PID_xxxx&MI_xx`、兼容 ID 是
`USB\Class_08&SubClass_06&Prot_50`，那就是本文这种情况：
移除那条 ADB 驱动包，让 Windows 用系统自带的 USB 大容量存储驱动即可。
