# native/

`pstrim.c` —— 随包的空间回收工具（打洞），编译成静态 aarch64 可执行文件，
打包进 `app/src/main/assets/pstrim`，运行时由 App 释放到 `/data/local/tmp` 执行。

支持的文件系统与"空闲块表"来源：

| 文件系统 | 空闲块来源 |
|---|---|
| FAT32 | FAT 表项 == 0 |
| exFAT | Allocation Bitmap（簇位图） |
| NTFS | `$Bitmap`（MFT 记录 6 的数据流，需解析 runlist） |
| ext4 | 各组 block bitmap（`meta_bg` 已适配，`BLOCK_UNINIT` 组跳过） |

分区布局：MBR / GPT / 无分区表（超级软盘），多分区逐个处理。

## 编译

```bash
aarch64-linux-gnu-gcc -static -O2 -Wall -o pstrim pstrim.c
cp pstrim ../app/src/main/assets/pstrim
```

## 用法

```bash
pstrim <image>              # 回收（就地打洞）
pstrim <image> --dry-run    # 只统计不落盘
pstrim --fitrim <挂载点>     # 对已挂载文件系统发 FITRIM（备用路径）
```

输出示例：

```
STATUS=OK fs=fat32,ext4 layout=partition parts=2 trimmed=2 skipped=0 \
          runs=25 punched=294838272 before=419844096 after=125005824 dry=0
```

## 安全约束

- 只对文件系统明确标记为"空闲"的块打洞，坏簇/保留值一律不动；
- 打洞区间按 4K 对齐（f2fs 块大小），避免误伤相邻在用块；
- ext4 `BLOCK_UNINIT` 的组（位图未初始化）整组跳过；
- 镜像正被 LUN 占用时不要执行（App 侧会拦截并提示先卸载）。

## 诊断开关

```bash
pstrim <image> --dry-run   # 只统计，不落盘
pstrim <image> --stats     # 打印 ext4 位图/元数据保护区的统计
pstrim <image> --map       # 打印镜像实际分配的区间（SEEK_DATA/SEEK_HOLE）
```

回收流程分三步：
1. 按文件系统空闲块表打洞（FAT32/exFAT/NTFS/ext4 各自实现）；
2. ext4 额外把 `BLOCK_UNINIT` 组按"除元数据外空闲"处理；
3. 零块扫描：遍历实际分配区间，把内容全零的 4K 块也打洞
   （inode 表、保留 GDT 这类零填充元数据就靠这一步压掉）。
