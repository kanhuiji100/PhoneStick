# 稀疏镜像方案研究与实现记录

- 起始：2026-10-26（针对 PhoneStick 2.3.1 的固定大小 raw 镜像）
- 现状：已实现并验证到 **2.3.7**
- 设备：Redmi K20 Pro (raphael)，Android 17 / kernel 4.14.357（LineageOS 系 ROM）
- 一句话结论：**qcow2/VDI 内核不支持、本机内核也跑不了；用「稀疏 raw」达到同样效果，
  再配合打洞工具回收已删除数据的空间**

## 目录

| 部分 | 章节 | 内容 |
|---|---|---|
| **方案研究** | 一 ～ 五 | gadget 认不认 qcow2、四个候选方案对比、NBD/FUSE 路线为何放弃 |
| | 六 ～ 七 | 落地建议、证据清单 |
| **实现** | 八 | 2.3.2 稀疏创建（truncate）+ 占用显示 |
| | 九 | 2.3.3 空间回收（pstrim + FAT32） |
| **缺陷复盘** | 十 | 2.3.4 文件管理器永远显示 2GB（MediaStore/FUSE 滞后） |
| | 十一 | 2.3.5 裸盘镜像不被识别为稀疏（`used > 0` 判定错误） |
| | 十二 | 2.3.6/2.3.7 回收扩展到 exFAT / NTFS / ext4，补 GPT 与无分区表 |

---

## 一、先回答核心问题：gadget 认不认 qcow2 / VDI？

**不认，而且这条路在内核层是死的。**

内核文档 `Documentation/usb/mass-storage.rst` 原文：

> Backing storage for each LUN is provided by **a regular file or a block device**

f_mass_storage（`file-storage` 内核线程）拿到 LUN 路径后，直接对它做
`read/write`（按字节偏移）。它**没有任何磁盘容器格式的解析能力**：
loop 设备不支持 qcow2，device-mapper 不支持，CONFIG_TARGET_CORE 未编译，
CUSE（用户态字符设备）也未开启。凡是 qcow2/VDI/VHD 这类格式，
都必须在**手机侧用户态**先翻译成"裸块访问"，内核侧没有捷径。

另外要澄清一个常见误解：**qcow2 / VHD dynamic 的"动态"指的是物理文件按需增长，
虚拟容量同样是建盘时固定的**（qcow2 的 virtual size 写死在 header 里）。
也就是说，用户能从"动态磁盘"里得到的核心收益只有一个——
**"盘看起来 8GB，手机实际只占已写入的那几百 MB"**。
而这个收益，稀疏 raw 镜像就能完整提供，不需要任何新格式。

---

## 二、四个候选方案对比

| 方案 | 原理 | 本机可行性 | 性能 | 工程量 | 结论 |
|---|---|---|---|---|---|
| **A. 稀疏 raw 镜像** | `truncate` 创建空洞文件，写多少占多少 | ✅ 零依赖 | ≈ 原生 | 几行 shell | **推荐，立即可做** |
| B. qemu-nbd + 内核 NBD | 用户态 qemu-nbd 把 qcow2/VDI 翻译成 `/dev/nbdX`，LUN 指向它 | ❌ 内核未编 `CONFIG_BLK_DEV_NBD` | 先例实测 **损耗 80–90%** | 自编内核 + 移植 qemu | 不推荐 |
| C. FUSE 翻译层 | 自研 daemon 把 LUN 的读写翻译成 qcow2 操作 | ⚠️ 内核 FUSE 有（`CONFIG_FUSE_FS=y`） | 先例实测 **损耗 ~85%** | 自研 C/FUSE daemon | 理论可行，不推荐 |
| D. 在线扩容（改虚拟容量） | 挂载中改文件大小，期待主机重读容量 | ❌ f_mass_storage 容量在 LUN 打开时定格；挂载中改 = 数据损坏 | — | — | 排除（qcow2 同样做不到） |

---

## 三、方案 A：稀疏 raw 镜像（推荐）

### 3.1 原理

`truncate -s 8G image.img` 创建一个"逻辑 8GB、实际 0 块"的空洞文件：

- gadget 读空洞 → VFS 返回全零页（对主机就是"空白盘区"）✅
- 主机写数据 → f2fs 按需分配块，手机存储占用随之增长 ✅
- 主机端看到的一切（容量、分区、文件系统）与普通 raw 镜像**完全一致**，
  Windows / Linux / macOS 主机侧零改动 ✅

f2fs 对稀疏文件的支持是有内核证据的：`SEEK_HOLE/SEEK_DATA` 自 2014 年
（kernel 3.16 时代）即实现，`punch_hole`/`f2fs_truncate_hole` 一直在
`fs/f2fs/file.c` 主线维护；本机 ext4 上实测行为如下（f2fs 同机制）：

```
$ truncate -s 2G sparse_test.img
$ du -h sparse_test.img
0    sparse_test.img            ← 创建瞬间完成，0 块占用（对比 dd 要跑几分钟）
$ dd if=/dev/urandom of=sparse_test.img bs=1M count=10 conv=notrunc
$ du -h sparse_test.img
10M  sparse_test.img            ← 写多少占多少
$ dd if=sparse_test.img bs=1M skip=1000 count=1 | od -An -tx1 | head -1
00 00 00 00 ...                 ← 空洞读出全零，与"未写过的盘区"一致
```

### 3.2 对现有代码的影响（好消息：几乎是白捡的）

| 环节 | 现状 | 影响 |
|---|---|---|
| `ImageCreator.createBlankImage()` | `fallocate`/分块 `dd` 实打实写满 | **改为 `truncate -s ${size}M`**，秒建、无 OOM —— 顺带把遗留问题 **P5（>2GB 镜像 OOM）直接消灭** |
| `writeMbr()` 动态分区扇区数 | 按 `stat -c %s` 实际大小计算 | **无需改动**：`stat %s` 对稀疏文件返回的是逻辑大小，正好是主机看到的容量 |
| `verifyPartition()` 原始字节校验 | dd 直读镜像 | 无需改动（读空洞得全零，语义不变） |
| SELinux | `/data/media/0/` + `media_rw_data_file` + `chmod 0666` | 无需改动（文件语义没变，只是有空洞） |
| LUN 绑定 / Strategy 1 | 直写 `lun.0/file` | 无需改动 |

`fallocate` 要从默认路径移除：它在 f2fs 上是**真实预分配**，正是当前
镜像占满空间的根源；保留它等于自己把稀疏打掉。

改动示意（`ImageCreator.kt`）：

```kotlin
val r = Shell.cmd(
    "truncate -s ${sizeInMB}M '$escapedPath'",
    "chown 1023:1023 '$escapedPath' 2>/dev/null || true",
    "chmod 0666 '$escapedPath' 2>/dev/null || true"
).exec()
// 校验: stat -c %s == totalBytes 即成功；失败再回落到现有 dd 分块路径
```

### 3.3 UI 建议配套

- 列表项显示两个值：**容量**（`stat -c %s`，逻辑大小）和**实际占用**
  （`du -k`；`stat -c %b` 在 toybox 上支持不稳，优先 du）。
- 顺便解决"选了 16GB 镜像但手机只剩 3GB"的场景：创建从"必然失败"变成
  "创建成功、写满才报错"，建议在剩余空间低于逻辑容量 20% 时提示。

### 3.4 实测验证（2026-10-26 已完成，端到端全绿）

测试环境：Redmi K20 Pro (raphael, Android 17, kernel 4.14.357) + 本 Linux 主机，
镜像 `/data/media/0/PhoneStick/sparse_test.img`（f2fs 上，`truncate -s 4G` 创建）。
完整链路：**稀疏创建 → 手机端 writeMbr+newfs_msdos 格式化 → 绑 LUN →
主机分区识别/挂载 → 主机写 200MB → 手机侧占用增长 → 错误检查 → 还原**。

| 环节 | 结果 | 实测数据 |
|---|---|---|
| f2fs 稀疏创建 | ✅ | `truncate -s 4G` 秒建：`stat %s`=4294967296，`du -k`=0，`stat %b`=0 |
| SELinux 标签 | ✅ | 新文件自动继承 `media_rw_data_file`（`ls -Z` 确认） |
| 空洞读取 | ✅ | 偏移 1GB 处读出全零；4MB 空洞块 md5 与 `/dev/zero` 一致 |
| 写入按需分配 | ✅ | 写 20MB 随机数据 → `du -k` 精确从 0 → 20504K |
| 手机端格式化（App 2.3.1 路径） | ✅ | losetup → `blockdev --getsize64 p1` = 4293918720（分区表对得上）→ `newfs_msdos -F 32 -c 1` rc=0 → blkid `TYPE="vfat"` → 原始字节偏移 1MB 处 FAT32 魔数命中 |
| 格式化一次性成本 | ✅ 可接受 | `du` 20504K → 65624K（**+45MB**，即 4GB FAT32 的两张 FAT 表，≈1.1%） |
| 主机识别 | ✅ | `sdb` = Linux File-Stor Gadget，4.00 GiB / 8388608 512-blocks（**容量取自逻辑大小，稀疏对主机完全透明**）；`sdb1` 正常出现（"capacity change from 0 to 8388608" 正常时序） |
| 主机挂载/写入 | ✅ | vfat rw 挂载；写入 200MB 随机数据 + 中文文本文件，耗时 10.1s（**≈20.8 MB/s**，USB2 正常水位） |
| 数据完整性 | ✅ | 回读 md5 与源文件一致；中文内容无损；手机侧 `grep -a` 镜像原始字节命中写入文本 |
| **核心命题：按需增长** | ✅ | 写入 200MB 后手机 `du -k` = 65624 → **270636K**（264MB），`stat %b` = 541272 块；逻辑大小不变 |
| 错误检查 | ✅ | 手机 dmesg：file-storage avc denied = **0**，无 I/O error / Medium Error；主机 dmesg：sdb 无 Sense/错误 |
| 还原 | ✅ | LUN 清空、`sys.usb.config adb`、配置链接回到仅 ffs.adb，与测试前一致 |

结论：**方案 A 端到端全部验证通过，可以进入代码实现。**

附带实测发现（写实现时要注意）：

1. **toybox `dd` 不支持 `conv=sparse`**（报 `bad conv=sparse`）——手机端没有
   保稀疏的复制手段，镜像在手机侧的任何 `cp`/`dd` 复制都会膨胀到逻辑大小。
   导入/备份要么接受膨胀，要么在 PC 端做（`cp --sparse=always` / `rsync -S` /
   `qemu-img convert -O raw`）。
2. 主机侧 vfat 挂载默认属 root（udisks/手动 mount 均如此），用户态直接写
   会静默失败——这是测试环境问题，与稀疏方案无关，但 App 文档无需涉及。
3. `stat -c %b` 在本机 toybox 上可用，UI 显示"实际占用"可以直接用
   `%b*512` 或 `du -k`（后者更稳）。

### 3.5 已知代价（要写进用户文档）

| 操作 | 行为 | 说明 |
|---|---|---|
| 把镜像从手机拷出（MTP / SAF / App 导入路径的 `cp`） | **稀疏性丢失，膨胀到逻辑大小** | 想保稀疏：在 PC 上用 `cp --sparse=always` / `rsync -S` / `qemu-img convert -O raw`；手机端 toybox `cp` 不保稀疏 |
| 主机格式化 FAT32 | FAT 表会写满全盘 → 立即分配几十 MB | 正常，一次性成本 |
| 备份到手机其他位置 | 同上，膨胀 | 建议只在 PC 端备份 |
| 镜像导入（App 现有 `cp` 路径） | 落地为非稀疏 | 接受；进阶可试 `dd conv=sparse`（toybox 版本支持情况需先验证 `dd --help`） |

---

## 四、方案 B：qcow2/VDI 真支持（qemu-nbd + NBD）——为什么排后

这条是"正牌"做法，链路为：`qcow2/VDI 文件 → qemu-nbd(用户态) → /dev/nbd0(内核 NBD 块设备) → lun.0/file`。内核文档确认 LUN 可以指向块设备，所以最后一环是通的。断点在前面：

1. **本机内核没有 NBD**。已核实两份配置：
   - 小米开源 `raphael-p-oss`（`raphael_user_defconfig`）：无 `CONFIG_BLK_DEV_NBD`
   - LineageOS `android_kernel_xiaomi_sm8150` lineage-23.2（`sm8150_defconfig` + `raphael.config`）：同样无 NBD、无 CUSE；有 `FUSE_FS=y`、`BLK_DEV_LOOP=y`、`BLK_DEV_DM=y`
   - 补齐方式只有自编内核（或给 4.14.357 精确匹配编译 `nbd.ko` 模块——
     vermagic/CONFIG_MODVERSIONS 都要对上，ROM 内核不开源对应树就编不了）。
2. **qemu-nbd 要移植到 Android**（aarch64 静态编译，带 glib 依赖，
   或塞进 Termux 环境），并且要作为常驻守护进程随挂载启停。
3. **性能先例很惨**：移动取证场景有人在 Android 上实测过同类方案
   （qemu-nbd + 自编内核），顺序写 5.1 MB/s vs 原生 52.3 MB/s，
   **损耗 ~90%**；FUSE 方案实测也是 85–90% 损耗。USB2 BOT 本身只有
   ~30–40 MB/s，再叠 90% 就完全不可用了。
4. **可靠性风险**：qemu-nbd 进程死亡 = 主机立刻开始吃 I/O error，
   正在写盘的主机端文件系统可能损坏；还要处理 SELinux 对 `/dev/nbd0`
   的标签（kernel 域打开块设备，未验证，大概率要 `chcon`）。

**qcow2 相比稀疏 raw 的独占收益**只有：压缩（zlib/zstd）、快照/增量、
加密、backing file。除非明确要做这些功能，否则不值得付出上面全部代价。
（顺带：f2fs 的 per-file compression 能在块层压镜像，但它 5.4+ 才有，
本机 4.14 内核没有。）

已有的 qcow2 资产想在 PhoneStick 用：PC 上
`qemu-img convert -O raw disk.qcow2 disk.img` 转成稀疏 raw 再导入即可。

---

## 五、方案 C：FUSE 翻译层（自研）——理论可行，风险最高

内核 FUSE 可用，思路是：自研一个 daemon，把某个 qcow2 暴露成 FUSE 挂载里的
一个"普通文件"（大小 = 虚拟容量），LUN 指向该 FUSE 文件；gadget 的
`file-storage` 内核线程对这个文件的 read/write 会被转发到 daemon，
由 daemon 完成 qcow2 的簇映射翻译。

- 优势：不动内核、真支持 qcow2/VDI 语义、还能做压缩/快照。
- 风险：`u:r:kernel:s0` 域直接读写 FUSE 文件这条路没有主流验证
  （挂载时用 `context=u:object_r:media_rw_data_file:s0` 可以解决标签问题，
  但 file-storage 线程阻塞在 daemon 上的行为未验证）；daemon 崩溃 =
  挂载中的盘瞬间全 I/O error；性能同 B 档（多一次用户态往返）。
- 结论：除非将来要做"qcow2 快照管理器"这种功能型卖点，否则不碰。

---

## 六、落地建议

1. **立即做（方案 A，实测已全绿，见 3.4）**：
   - `ImageCreator.createBlankImage()` 改 `truncate`（保留 dd 兜底），
     删除 `fallocate` 默认路径；
   - 列表/详情显示"容量 / 实际占用"两个值（`stat -c %b` 或 `du -k`）；
   - P5（大镜像 OOM）随之关闭，可在文档里标记解决。
2. **不做**：qcow2/VDI 原生支持（方案 B/C）。结论写进 FAQ：
   "为什么不用 qcow2？——内核 gadget 只认裸文件/块设备，qcow2 需要
   用户态翻译，实测性能损耗 80–90% 且需要自编内核；稀疏 raw 在
   '省空间'这个需求上与 qcow2 等价，且零性能损耗。"
3. 用户已有的 qcow2/VDI：文档给一条 PC 端转换命令
   （`qemu-img convert -O raw xxx.qcow2 xxx.img`，产出即为稀疏）。

---

## 七、证据清单

| 事实 | 来源 |
|---|---|
| LUN 后备只能是普通文件或块设备 | kernel 文档 `Documentation/usb/mass-storage.rst`（docs.kernel.org） |
| qemu-nbd 的用法与内核 NBD 依赖 | qemu 官方 `docs/tools/qemu-nbd.rst`（需 `modprobe nbd`） |
| raphael stock 内核无 NBD/CUSE，有 FUSE/LOOP/DM | MiCode `Xiaomi_Kernel_OpenSource@raphael-p-oss` `raphael_user_defconfig`（2026-10-26 拉取） |
| LineageOS 23.2 sm8150 内核同样无 NBD/CUSE | `LineageOS/android_kernel_xiaomi_sm8150@lineage-23.2` `sm8150_defconfig` + `vendor/xiaomi/raphael.config` |
| Android 上 qemu-nbd 方案实测损耗 ~90%、需重编内核、SELinux 限制 | CSDN《从取证到性能陷阱：Android VHD挂载的工程实践与优化迷思》(2026-02) |
| f2fs 支持稀疏空洞 / SEEK_HOLE / punch hole | kernel 邮件列表 f2fs 补丁历史（2014 起）+ `fs/f2fs/file.c` |
| 稀疏文件"秒建、按需增长、空洞读零" | 本机 Linux 实测（/tmp，见 3.1） |
| **f2fs 上稀疏镜像全链路可用**（truncate → 格式化 → LUN → 主机挂载写 200MB → 占用按需增长、零 I/O 错误） | **本机手机端到端实测（见 3.4），2026-10-26** |
| toybox `dd` 无 `conv=sparse` | 设备实测：`dd: bad conv=sparse` |

---

## 八、代码实现（2.3.2，2026-10-26）

### 8.1 改动清单

| 文件 | 改动 |
|---|---|
| `ImageCreator.kt` | 新增 `sparse: Boolean = true` 参数；默认走 `rm -f` + `truncate -s <bytes>`；失败才回落到 `fallocate`/分块 `dd`。预分配路径保留（取消勾选稀疏时使用） |
| `ImageStore.kt` | `Info` 增加 `allocatedBlocks`（`stat %b`）与 `usedBytes`/`isSparse`；`queryAll` 输出从 4 字段扩到 5 字段；新增 `formatSizeWithUsage()` |
| `ImageFilesAdapter.kt` | 新增 `usedProvider`，列表显示 `8.00 GB · 126 MB used`（占用 ≥95% 逻辑大小时自动省略，避免非稀疏镜像重复显示） |
| `ImageChooserActivity.kt` | 新增 `fileUsedSizes` 缓存；创建对话框开关语义从 "Fast Allocation (fallocate)" 改为 "Sparse image" |
| `MainActivity.kt` | 新增 `selectedUsedBytes`，选中项路径行同样显示实际占用 |
| `dialog_create_image.xml` | 开关文案改为 `Sparse image (allocate on demand)`，并加一行说明文案 |
| `app/build.gradle` | `versionCode 14` / `versionName 2.3.2` |

关键实现细节：`truncate` 校验仍用 `stat -c %s`（返回逻辑大小），因此
`writeMbr()` 的动态分区扇区数计算、`verifyPartition()` 的原始字节校验
**一行都不用改**——这是方案 A 最省事的地方。

### 8.2 App 内真机验证（2.3.2）

在同一台 K20 Pro 上用 UI 走完真实用户流程（新建 → 挂载 → 主机写入）：

| 步骤 | 结果 |
|---|---|
| 创建 8GB / FAT32 / 稀疏 | 点击 Create 后 **约 2 秒**完成；logcat：`sparse creation succeeded (truncate)` → `writeMbr: [MBR_OK]` → `format via loop succeeded` → `verifyPartition: VERIFY_OK` |
| 文件实际占用 | 逻辑 `8589934592` 字节，`du -k` = **129196K（126 MB）**，仅为 8GB 的 1.5%（FAT 表成本） |
| 列表/主页显示 | `8.00 GB · 126.17 MB used • /sdcard/PhoneStick`；非稀疏的 `qqq.img` 仍只显示 `1.00 GB` |
| App 内挂载 | LUN 指向 `sparse8g.img`，`configs/b.1` 出现 `mass_storage.0`，主机侧 `sdb` = 8.00 GiB、`sdb1` vfat |
| 主机写入 100MB | 5.4s（**≈18.5 MB/s**），回读 md5 与源文件一致 |
| 占用增长 | `du -k` 129196 → **231704**（精确 +100MB），逻辑大小不变 |
| 还原 | 卸载后 LUN 空、`sys.usb.config=adb`、测试镜像删除、主机无盘 |

**P5（>2GB 镜像 OOM）正式关闭**：8GB 镜像创建全程不写数据块，
内存占用与镜像大小无关。

### 8.3 实施中发现的既有行为（非本次改动引入）

- **只读开关默认是打开的**（`activity_main.xml` 里 `switchReadOnly` 的
  `android:checked="true"`）。因此 App 挂载出来的盘默认写保护，
  主机侧 `mount` 会提示 `source write-protected, mounted read-only`。
  要不要把默认值改成关闭，属于产品决策，本次未动。


---

## 九、空间回收：删了文件怎么把空间还回来（2.3.3）

### 9.1 问题

稀疏镜像只是「写多少占多少」。主机删除文件时，FAT32 仅把簇标记为空闲，
**镜像文件里那些块依然占着手机存储**。实测：写入 400MB 后删掉其中 300MB，
`du` 仍然纹丝不动（463MB）。

要让块真正释放，只能**打洞**（`fallocate(PUNCH_HOLE)`）：
写零没用——写零会重新分配物理块，占用反而更实。

### 9.2 为什么不能用现成工具

| 候选 | 结果 |
|---|---|
| `fallocate -d`（util-linux 的打洞模式） | 设备上的 **toybox 0.8.13 只支持 `-l/-o`**，实测报 `Unknown option 'd'` |
| `dd conv=sparse` | toybox dd 不支持（`bad conv=sparse`） |
| `cp` 后 `fstrim` | vfat 不产生 discard 请求，fstrim 对镜像文件无效 |
| Java/NIO API | 没有 punch hole 接口，`android.system.Os` 也未暴露 `fallocate` |
| 自研 FUSE/内核模块 | 代价远超收益 |

### 9.3 方案：随包 pstrim（静态 aarch64 小工具）

`tools/pstrim.c`（约 250 行）编译成 700KB 静态二进制，打进 `assets`，
运行时释放到 `/data/local/tmp/pstrim` 以 root 执行：

1. 解析 MBR → 分区起始 LBA；解析 FAT32 BPB（`512B` 扇区、簇大小、保留扇区、FAT 数/大小）；
2. 逐簇读 FAT，值为 `0` 即空闲簇（坏簇/保留值不动，保守处理）；
3. 连续空闲簇折算成字节区间，按 **4K 对齐**后 `fallocate(PUNCH_HOLE|KEEP_SIZE)`；
   —— 4K 对齐是关键：f2fs 块大小 4K，非对齐打洞会把边界所在的整块清零，
      可能波及相邻的在用簇；
4. 顺带把头部（MBR/引导扇区/FAT 表）里**全零的 4K 块**也打掉（FAT 表里大量空闲项）；
5. 不重写任何数据、不需要额外空间，耗时只与 FAT 大小相关。

安全前提：镜像不能正被 LUN 占用（App 侧检查 `getMountStatus()` 并提示先卸载）。

只支持 FAT32（App 默认格式）。ext4/exFAT 的回收机制完全不同（ext4 可用
`e2fsck` 的 discard 或 fstrim，但镜像不是块设备），暂不做。

### 9.4 实测（同一台 K20 Pro，4GB 稀疏镜像）

| 步骤 | 结果 |
|---|---|
| 创建 4GB 稀疏 FAT32 | 63.09 MB 占用 |
| 主机写入 300MB + 100MB | 463.49 MB |
| 主机删除 300MB | **463.49 MB（完全没释放，问题复现）** |
| App 菜单「释放未使用空间」 | **102.06 MB**，`pstrim` 输出 `runs=15984 punched≈3.9GB before=486MB after=107MB` |
| 重新挂载校验 | `keep.bin` md5 与源文件一致；`fsck.vfat` 报告 `1 files, 204801/8257504 clusters`，结构正常 |
| UI 反馈 | 列表项自动刷新为 `4.00 GB · 102.06 MB used`，并弹出「已释放 xxx，现占用 xxx」 |

主机侧单机验证（x86_64 同源代码）：512MB 镜像写入 232MB → 删除 200MB →
`pstrim` 后 `du` 从 238MB 降到 **32MB**，`fsck.vfat` 干净、剩余文件 md5 一致。

### 9.5 入口与限制

- 菜单项只对**稀疏镜像**显示（占用 < 逻辑容量 95%），预分配镜像没有可回收的块。
- 镜像被挂载时点击 → 提示「请先卸载 U 盘再整理镜像」。
- 只能回收「已被文件系统标记为空闲」的块；被 FAT 判定为占用的区域一律不动，
  因此不会破坏数据。

---

## 十、Bug 修复：文件管理器永远显示 2GB（2.3.4）

### 10.1 现象

用户反馈：创建镜像时选「裸盘（无文件系统）」，创建出来的不是稀疏镜像，
而且用文件管理器看大小**一直是 2GB**。

### 10.2 排查

先排除创建逻辑：真机新建 6GB 裸盘镜像，底层 `stat` = 6442450944、`%b` = 0
（**确实是稀疏的**，占用 0）。问题不在创建。

再对比三个视角，抓到真凶：

| 视角 | 结果 |
|---|---|
| 底层 `/data/media/0/PhoneStick/phonestick_drive.img` | 4294967296（4GB）、0 块（稀疏） |
| FUSE `/sdcard/PhoneStick/...`（文件管理器看的就是它） | **2147483648（2GB）**，mtime 还是旧文件的 09:35 |
| MediaStore `content query` | `_size=2147483648` —— 09:35 那条旧记录 |

**根因**：镜像一直由 root 直接在 `/data/media/0` 下 `truncate`/重建，
MediaProvider 完全不知情。同名文件重建后，MediaStore 里旧记录没有被刷新，
FUSE 的 getattr 直接返回记录里的旧 size，于是文件管理器永远显示旧大小（2GB）。
MediaProvider 不重启就不会自愈（实测 `killall com.android.providers.media.module`
后立刻变回 4GB）。

这也解释了「看起来不是稀疏镜像」：用户看到的是那个 2GB 的旧数字/旧文件，
而不是新建的稀疏文件。

### 10.3 修复

关键实验结论：**通过 FUSE 路径（`/sdcard/...`）对同一文件做一次同长度
`truncate`，MediaProvider 会立刻更新记录与属性缓存**（实测 1GB→3GB 立即生效，
root 身份同样有效）。

落到代码里：

| 位置 | 改动 |
|---|---|
| `ImageStore.syncFuseMetadata()` | 新增：取真实大小，通过 `/sdcard/...` 做一次同长度 truncate |
| `ImageStore.repairFuseMetadata()` | 新增：批量比对 FUSE 与真实大小，不一致就重设（自愈历史脏记录） |
| `ImageCreator` | 创建完成后调用 sync |
| 删除 | 改为优先走 `/sdcard` 路径 `rm`，MediaStore 记录一起清掉（不会留幽灵条目） |
| 重命名 | 改为走 `/sdcard` 路径 `mv`，记录跟着改名 |
| 导入 | 复制完成后调用 sync |
| 图片列表加载 | 调 `repairFuseMetadata()`，打开一次列表就能修好升级前留下的脏记录 |
| 稀疏创建失败 | 不再悄悄回落到「写满整盘」的预分配，直接报错并提示可取消勾选稀疏 |

### 10.4 实测

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 建 1GB 裸盘 | 2GB（旧记录） | 1GB ✅ |
| 删除后同名重建 3GB | 仍显示 1GB（FUSE 缓存） | **3GB ✅** |
| 删除镜像 | MediaStore 残留记录 | 记录同步删除（`No result found`）✅ |
| root 后台把文件改小，再开 App 列表 | 一直显示旧大小 | 自动校正 ✅ |

---

## 十一、Bug 修复：裸盘镜像不被识别为稀疏（2.3.5）

### 11.1 现象

裸盘镜像创建后：
- 列表里只显示 `2.00 GB`，没有「已用 / 未写入」后缀；
- 菜单里没有「释放未使用空间」。

### 11.2 根因

判定条件写成了「已用 > 0 且 已用 < 容量」：

```kotlin
val isSparse = used > 0 && size > 0 && used < size * 95 / 100
```

而**刚建好的裸盘镜像一个块都没分配**（`stat %b = 0`），`used == 0` 直接被
`used > 0` 挡掉 —— 最彻底的稀疏反而被判成「非稀疏」。
FAT32 镜像因为格式化会写 FAT 表（4GB 约 63MB），`used > 0` 才侥幸正常。

### 11.3 修复

- 判定改成 **`used < size`**（0 占用也算稀疏），统一收进
  `ImageStore.isSparse(size, used)`，列表和菜单都用它；
- 展示补一档：`used == 0` → `2.00 GB · 未写入`；有数据 → `4.00 GB · 已用 63.09 MB`
  （顺手把英文 "used" 换成中文）；
- 空镜像点回收直接返回成功并提示「该镜像没有可释放的空间」，
  不再跑 pstrim；
- 非 FAT32 的错误文案改成可操作的建议（先分区格式化再整理）。

### 11.4 实测

| 镜像 | 列表显示 | 菜单 |
|---|---|---|
| raw2g.img（2GB 裸盘，0 占用） | `2.00 GB · 未写入` | 有「释放未使用空间」，点击提示「该镜像没有可释放的空间」 |
| fat.img（4GB FAT32 稀疏） | `4.00 GB · 已用 63.09 MB` | 有，正常 |
| qqq.img（1GB 预分配） | `1.00 GB` | 无（非稀疏，符合预期） |
| phonestick_drive.img（4GB 裸盘） | `4.00 GB · 未写入` | 有 |

---

## 十二、回收功能扩展到 exFAT / NTFS / ext4（2.3.7）

### 12.1 思路

「释放未使用空间」的本质是：拿到文件系统的**空闲块表**，把空闲区间
按 4K 对齐打洞。不同文件系统的空闲块表位置完全不同：

| 文件系统 | 空闲块来源 | 判定 |
|---|---|---|
| FAT32 | FAT 表 | 表项 == 0 即空闲 |
| exFAT | Allocation Bitmap（簇位图） | 位 == 0 即空闲 |
| NTFS | `$Bitmap`（MFT 记录 6 的数据流） | 位 == 0 即空闲 |
| ext4 | 各 block group 的 block bitmap | 位 == 0 即空闲 |

另外镜像可能来自 PC，分区布局有三种：**MBR / GPT / 超级软盘（无分区表，
文件系统直接在偏移 0）**，三种都要认。

### 12.2 实现要点

- `pstrim` 先定位数据区（MBR→第一个非空分区；0xEE→读 GPT 头拿第一个分区的
  first/last LBA；无可用分区→按超级软盘处理），再按引导扇区魔数识别文件系统；
- 每种 FS 各自的解析器，统一输出「空闲字节区间」交给同一个 4K 对齐打洞函数；
- ext4 的 `meta_bg` 特性会让组描述符位置变化，按 e2fsprogs 的
  `ext2fs_descriptor_block_loc2()` 算法实现（描述符块位于该 meta group 首块 +
  备份超级块偏移）；`BLOCK_UNINIT` 的组保守跳过（位图未初始化，不碰）；
- NTFS 需要解析 MFT runlist 才能把 `$Bitmap` 拼出来（支持多段非连续数据流）。

### 12.3 踩到的坑（都是靠 md5 校验抓出来的）

| 坑 | 后果 | 修正 |
|---|---|---|
| 把 `s_feature_compat (0x5C)` 当成 `s_feature_incompat (0x60)` | `RESIZE_INODE` 被误判成 `META_BG`，组描述符寻址全错，**打洞打到在用数据上**（keep.bin md5 变了） | 改用 0x60 |
| GPT 头字段偏移记错（88/96/100） | GPT 镜像识别失败 | 改为 72/80/84 |
| 几何校验把「分区内偏移」和「整盘偏移」混用 | 合法的 MBR 镜像被拒（-4） | 统一改成相对分区起点 |
| 想用 `mount` + `FITRIM` 解决 ext4 | 设备上 `losetup -o` 写正常、**读返回 0 字节**，mount 失败 | 放弃挂载路线，老老实实解析位图 |

### 12.4 验证矩阵（主机侧，512MB 镜像，写入 250MB → 删除 200MB）

| 文件系统 | 布局 | 结果 | 整理后 | keep.bin md5 |
|---|---|---|---|---|
| FAT32 | MBR / GPT / 超级软盘 | OK | 52MB | 一致 |
| exFAT | MBR / GPT / 超级软盘 | OK | 53MB | 一致 |
| NTFS | MBR / GPT / 超级软盘 | OK | 54MB | 一致 |
| ext4 | MBR / GPT / 超级软盘 | OK | 60~92MB | 一致 |

每种组合都做了「整理后重新挂载 + md5 对比」，8/8 通过。

### 12.5 真机验证（K20 Pro）

- App 内新建 1GB **ext4** 镜像 → 挂载 → 主机写入 300MB + 50MB → 删除 300MB：
  `382.57 MB → 82.50 MB`，`pstrim: fs=ext4 runs=263`，
  重新挂载 `keep.bin` md5 一致、`e2fsck -fn` 干净（21023/261888 blocks）；
- 对现有真实镜像做 `--dry-run`（只统计不落盘）：
  `fat.img` → fat32/partition、`exfat.img` → exfat/superfloppy、
  `ntfs.img` → ntfs/superfloppy、`ext4.img` → ext4/GPT，全部识别成功；
- 删掉 `/data/local/tmp/pstrim` 后再点回收，App 会自动重新释放二进制并正常执行。

### 12.6 仍然不支持的

- 未识别文件系统（如 f2fs、btrfs、APFS）→ 提示"无法识别文件系统"；
- ext4 的 `BLOCK_UNINIT` 组不回收（保守，宁可少回收也不冒险）；
- GPT 只取第一个非空分区（多分区镜像只处理第一个）。
