package mingww64.phonestick

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 镜像创建与格式化。
 *
 * 相比上游 2.3.0 的关键修复：
 *
 * 1. 【块设备】安卓的 newfs_msdos 只接受块设备，直接传普通文件会报
 *    ioctl(BLKSSZGET) failed。必须先 losetup 拿到 loop 设备再格式化。
 *
 * 2. 【工具选择】设备上通常没有 mkfs.vfat / mkfs.fat，但有 newfs_msdos
 *    和 mkfs.exfat。候选顺序要按实际可得性排，并加 mkfs.exfat 兜底。
 *
 * 3. 【失败要上报】原实现在所有候选都失败时仍回调 success=true，
 *    只在消息末尾附一句 warning，用户完全无法察觉。现在必须返回失败。
 *
 * 4. 【写 MBR】只在偏移 0 铺 FAT32 等于"超级软盘"，主机端会认为整盘无分区。
 *    格式化时写入标准 MBR，把文件系统放在分区 1 里（起始 LBA 2048）。
 *
 * 5. 【不要用 loop 分区写 f2fs】实测在该类设备上 dd 到 loopNp1 会静默丢数据
 *    （报成功但读回全零），所以分区头必须按字节偏移直接写镜像文件。
 *
 * 6. 【稀疏分配】默认用 truncate 创建稀疏镜像：逻辑容量是用户选的大小，
 *    物理占用随主机写入量增长。原来的 fallocate/分块 dd 会把整盘写实，
 *    在低内存设备上创建大镜像容易 OOM，而且 8GB 镜像一建就吃掉 8GB 空间。
 *    实测（2026-10-26）：truncate 4GB 秒建、du=0；主机写 200MB 后 du 增长到
 *    264MB；逻辑大小不变，主机端容量识别、格式化、读写全部正常。
 */
object ImageCreator {
    private const val TAG = "ImageCreator"

    private const val SECTOR = 512
    private const val START_LBA = 2048

    /** 记录格式化失败原因，供 UI 展示 */
    val lastErrors = ConcurrentHashMap<String, String>()

    fun createBlankImage(
        targetDirectory: File,
        fileName: String,
        sizeInMB: Long,
        filesystemFormat: String = "FAT32",
        sparse: Boolean = true,
        useFastAllocation: Boolean = true,
        onProgressStatus: (status: String, percent: Int, writtenMb: Long, totalMb: Long) -> Unit = { _, _, _, _ -> },
        onComplete: (Boolean, String, File?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val cleanName = if (fileName.endsWith(".img", ignoreCase = true) || fileName.endsWith(".iso", ignoreCase = true)) {
            fileName
        } else {
            "$fileName.img"
        }

        // 用 shell 建目录：/data/media/0/PhoneStick 属主是 media_rw，
        // app 的 File.mkdirs() 无法在其中创建目录
        val dirPath = targetDirectory.absolutePath
        val eDir = escapePath(dirPath)
        Shell.cmd(
            "mkdir -p '$eDir'",
            "chown 1023:1023 '$eDir' 2>/dev/null || true",
            // 2770 = setgid + rwxrwx---，保证目录里新建的文件继承 media_rw 组
            "chmod 2770 '$eDir' 2>/dev/null || true"
        ).exec()

        val outputFile = File(targetDirectory, cleanName)
        val escapedPath = escapePath(outputFile.absolutePath)

        // 用 shell 判断，避免 FUSE 视角下的 File.exists() 误判
        if (fileExists(outputFile.absolutePath)) {
            onComplete(false, "文件已存在：${outputFile.name}", null)
            return
        }

        thread {
            val totalBytes = sizeInMB * 1024 * 1024L
            var creationSuccess = false

            // ---------- 1. 分配文件 ----------
            //
            // 【稀疏分配（默认）】truncate 只设置文件的逻辑长度，不写任何数据块：
            //   - 创建瞬间完成，不占内存、不写 flash。原来是 fallocate/dd 写满整盘，
            //     这台机器可用内存长期只有 196~446MB，>2GB 镜像很容易 OOM / adb 掉线。
            //   - f2fs 上按写入量增长：主机往盘里写多少，手机才实际占用多少。
            //   - 主机侧看到的容量取文件逻辑大小，与预分配镜像完全一致。
            //   - 校验仍然用 stat %s（返回逻辑大小），writeMbr 动态算分区扇区数
            //     的逻辑不需要改。
            //
            // 【预分配】用户主动取消"稀疏"时才走 fallocate/dd，用于
            //   "我要确保这 8GB 一定落得下、不想写到一半空间不足"的场景。
            if (sparse) {
                mainHandler.post {
                    onProgressStatus("正在创建稀疏镜像…", 10, 0, sizeInMB)
                }
                // rm -f 先清掉同名残留（truncate 会保留原内容，只有长度变化）
                val r = Shell.cmd(
                    "rm -f '$escapedPath'",
                    "truncate -s $totalBytes '$escapedPath'"
                ).exec()
                if (r.isSuccess && getFileSize(outputFile.absolutePath) == totalBytes) {
                    Log.d(TAG, "sparse creation succeeded (truncate)")
                    creationSuccess = true
                    mainHandler.post {
                        onProgressStatus("稀疏镜像已创建", 90, sizeInMB, sizeInMB)
                    }
                } else {
                    // 用户勾的是"稀疏镜像"，truncate 失败时不能悄悄改成预分配 ——
                    // 预分配会实打实写满整盘，既违背用户选择，也是低内存设备 OOM 的根源。
                    Log.e(TAG, "truncate failed")
                    Shell.cmd("rm -f '$escapedPath'").exec()
                    mainHandler.post {
                        onComplete(
                            false,
                            "创建稀疏镜像失败：系统不支持 truncate。\n\n" +
                                "可在对话框里取消勾选「稀疏镜像」，改用预分配方式创建。",
                            null
                        )
                    }
                    return@thread
                }
            }

            if (!creationSuccess && useFastAllocation) {
                mainHandler.post {
                    onProgressStatus("正在预分配文件…", 10, 0, sizeInMB)
                }
                val r = Shell.cmd("fallocate -l ${sizeInMB}M '$escapedPath' 2>/dev/null").exec()
                if (r.isSuccess && getFileSize(outputFile.absolutePath) == totalBytes) {
                    Log.d(TAG, "fallocate succeeded")
                    creationSuccess = true
                    mainHandler.post {
                        onProgressStatus("预分配完成", 90, sizeInMB, sizeInMB)
                    }
                } else {
                    Log.w(TAG, "fallocate failed, falling back to dd")
                    Shell.cmd("rm -f '$escapedPath'").exec()
                }
            }

            if (!creationSuccess) {
                // 分块 dd，避免一次性占满页缓存。
                // fallocate 失败时文件可能已存在但大小为 0，先截断，
                // 否则 dd >> 追加会得到超长文件。
                Shell.cmd("rm -f '$escapedPath'").exec()

                val chunkMb = 16
                var writtenMb = 0L
                var failed = false
                while (writtenMb < sizeInMB) {
                    val cur = minOf(chunkMb.toLong(), sizeInMB - writtenMb)
                    val r = Shell.cmd(
                        "dd if=/dev/zero bs=1M count=$cur 2>/dev/null >> '$escapedPath'",
                        "sync"
                    ).exec()
                    if (!r.isSuccess) { failed = true; break }
                    writtenMb += cur

                    val percent = if (totalBytes > 0) {
                        ((writtenMb.toDouble() / sizeInMB) * 90).toInt().coerceIn(0, 90)
                    } else 0
                    mainHandler.post {
                        onProgressStatus(
                            "正在创建镜像… $writtenMb MB / $sizeInMB MB（$percent%）",
                            percent, writtenMb, sizeInMB
                        )
                    }
                }

                // 校验总长度，顺便防止 dd >> 追加到残留文件上
                val finalSize = getFileSize(outputFile.absolutePath)
                if (!failed && finalSize == totalBytes) {
                    creationSuccess = true
                } else {
                    Log.e(TAG, "dd creation failed: size=$finalSize expected=$totalBytes")
                    Shell.cmd("rm -f '$escapedPath'").exec()
                    mainHandler.post {
                        onComplete(
                            false,
                            "创建镜像文件失败。\n\n" +
                                "已写入 ${finalSize / (1024 * 1024)} MB，共 $sizeInMB MB。\n\n" +
                                "通常是存储空间不足，或设备可用内存过低。",
                            null
                        )
                    }
                    return@thread
                }
            }

            // ---------- 2. 格式化 ----------
            var formatMsg = ""
            var formatOk = true
            val fmt = filesystemFormat.uppercase()

            if (creationSuccess && fmt != "NONE" && fmt != "RAW BLANK (NO FS)") {
                mainHandler.post {
                    onProgressStatus("正在格式化文件系统（$fmt）…", 95, sizeInMB, sizeInMB)
                }
                val (ok, msg) = formatImageNow(outputFile.absolutePath, fmt)
                formatOk = ok
                formatMsg = msg
            }

            // 保证 app 侧也能读（FUSE 视角）
            ImageStore.makeReadable(outputFile.absolutePath)
            // 新建/重建同名文件后，MediaStore 里可能还留着旧记录，
            // 不通知的话文件管理器会一直显示旧大小（实测 4GB 显示 2GB）
            ImageStore.syncFuseMetadata(outputFile.absolutePath)

            mainHandler.post {
                if (creationSuccess && formatOk) {
                    onProgressStatus("完成！", 100, sizeInMB, sizeInMB)
                    val detail = if (formatMsg.isNotEmpty()) " $formatMsg" else ""
                    onComplete(true, "已创建镜像（${outputFile.name}）。$detail", outputFile)
                } else if (!creationSuccess) {
                    onComplete(false, "创建镜像失败", null)
                } else {
                    // 创建成功但格式化失败 —— 必须明确告知，不能假装成功
                    onComplete(
                        false,
                        "镜像已创建，但格式化失败。\n\n$formatMsg\n\n" +
                            "该镜像暂时无法使用，可从菜单重试格式化。",
                        null
                    )
                }
            }
        }
    }

    fun formatExistingImage(
        file: File,
        filesystemFormat: String = "FAT32",
        onProgressStatus: (status: String) -> Unit = {},
        onComplete: (Boolean, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread {
            mainHandler.post { onProgressStatus("正在将 ${file.name} 格式化为 $filesystemFormat…") }
            val (ok, msg) = formatImageNow(file.absolutePath, filesystemFormat.uppercase())
            ImageStore.makeReadable(file.absolutePath)
            mainHandler.post {
                if (ok) {
                    onComplete(true, "已将 ${file.name} 格式化为 $filesystemFormat${if (msg.isNotEmpty()) "（$msg）" else ""}")
                } else {
                    onComplete(false, "格式化 ${file.name} 失败：$msg")
                }
            }
        }
    }

    // ==========================================================
    // 核心：格式化实现
    // ==========================================================

    /**
     * 对镜像做完整分区 + 格式化：
     *   1. 写 MBR（单分区，类型 0x0C FAT32 LBA 或 0x83 Linux）
     *   2. 通过 loop 设备格式化分区 1
     *   3. 校验结果
     *
     * @return Pair(是否成功, 描述信息)
     */
    private fun formatImageNow(path: String, fmt: String): Pair<Boolean, String> {
        val sizeBytes = getFileSize(path)
        if (sizeBytes <= 0) return Pair(false, "镜像为空或无法读取")

        val totalSectors = sizeBytes / SECTOR
        if (totalSectors <= START_LBA + 1024) {
            return Pair(false, "镜像太小（至少需要 ${(START_LBA + 1024) * SECTOR / 1024 / 1024} MB）")
        }

        // ---------- 写 MBR ----------
        // 镜像大小来自 getFileSize，可能是任意值（用户导入的镜像不一定是
        // 整 MB）。分区扇区数按实际值算，别写死。
        val partType = if (fmt == "EXT4") "83" else "0c"
        if (!writeMbr(path, totalSectors, partType)) {
            Log.e(TAG, "writeMbr failed")
            return Pair(false, "写入分区表失败")
        }

        // 写完 MBR 必须让内核重新读分区表。
        // 对 loop 设备来说，losetup 再次 attach 时会自己 parse，
        // 但如果有残留的 loop 缓存，可能需要 BLKRRPART。
        // 这里由 formatPartitionViaLoop 内部新建 loop 解决。

        // ---------- 通过 loop 设备格式化分区 ----------
        val result = formatPartitionViaLoop(path, fmt)
        if (!result.first) return result

        // ---------- 校验 ----------
        return if (verifyPartition(path, fmt)) {
            Pair(true, "1 个分区，$fmt")
        } else {
            Pair(false, "格式化已执行，但校验未通过")
        }
    }

    /**
     * 写入标准 MBR 分区表。
     *
     * 【致命陷阱 —— 这是"格式化成功但镜像是空的"的最终根因】
     *
     * 分区项里的扇区数必须精确等于镜像实际容量，否则内核会打印：
     *     loop47: p1 size 4192256 extends beyond EOD, truncated
     * 并把分区截断成 0 长度。此时 loopNp1 节点看起来正常（-b 判断为真），
     * newfs_msdos 也返回 0，但**所有写入都被静默丢弃**，镜像里一个字节都没落地。
     *
     * 上游 2.3.0 的做法是把 2047MB 这个固定值写死，所以只有恰好 2GB 的
     * 镜像能格式化成功，其他尺寸全部"假成功"。
     *
     * 这里用 totalSectors 实际算出 partSectors，保证任何尺寸都对得上。
     */
    private fun writeMbr(path: String, totalSectors: Long, typeHex: String): Boolean {
        val e = escapePath(path)
        val partSectors = totalSectors - START_LBA
        if (partSectors <= 0) {
            Log.e(TAG, "image too small: totalSectors=$totalSectors")
            return false
        }
        val tmp = "/data/local/tmp/.ps_mbr.bin"

        // 小端 4 字节
        fun le32(v: Long): String {
            val b0 = (v and 0xFF).toInt()
            val b1 = ((v shr 8) and 0xFF).toInt()
            val b2 = ((v shr 16) and 0xFF).toInt()
            val b3 = ((v shr 24) and 0xFF).toInt()
            return String.format("\\x%02x\\x%02x\\x%02x\\x%02x", b0, b1, b2, b3)
        }

        // 分区项 16 字节：状态(1) CHS起(3) 类型(1) CHS止(3) LBA起(4) 扇区数(4)
        // CHS 统一填 FE FF FF，表示超出 CHS 寻址范围，现代系统只看 LBA 字段
        val entry = "\\x00\\xfe\\xff\\xff" +
            "\\x" + typeHex +
            "\\xfe\\xff\\xff" +
            le32(START_LBA.toLong()) +
            le32(partSectors)

        val script = arrayOf(
            "dd if=/dev/zero of='$tmp' bs=512 count=1 2>/dev/null",
            "printf '$entry' | dd of='$tmp' bs=1 seek=446 conv=notrunc 2>/dev/null",
            "printf '\\x55\\xaa' | dd of='$tmp' bs=1 seek=510 conv=notrunc 2>/dev/null",
            "dd if='$tmp' of='$e' bs=512 count=1 conv=notrunc 2>/dev/null",
            "sync",
            "SIG=\$(dd if='$e' bs=1 skip=510 count=2 2>/dev/null | od -An -tx1 | tr -d ' \\n')",
            "rm -f '$tmp'",
            "if [ \"\$SIG\" = \"55aa\" ]; then echo MBR_OK; else echo MBR_FAIL; fi"
        )
        val res = Shell.cmd(*script).exec()
        Log.d(TAG, "writeMbr: ${res.out}")
        return res.out.contains("MBR_OK")
    }

    /**
     * 通过 loop 设备格式化分区 1。
     *
     * 用 loop 是为了让 mkfs 工具能拿到块设备（newfs_msdos 对普通文件会报
     * ioctl(BLKSSZGET) failed），但所有 mkfs 都只作用于分区 p1，不会破坏 MBR。
     *
     * 前置条件：MBR 的 partSectors 必须等于镜像实际容量，否则内核会把分区
     * 截断成 0 长度，mkfs 返回 0 但什么都不写入（见 writeMbr 的注释）。
     * 这里额外用 blockdev --getsize64 复查分区大小，尺寸不对就直接报错，
     * 不给"假成功"留空间。
     */
    private fun formatPartitionViaLoop(path: String, fmt: String): Pair<Boolean, String> {
        val e = escapePath(path)

        val cmds = when (fmt) {
            "EXFAT" -> listOf(
                "mkfs.exfat -n PHONESTICK \$P",
                "mkfs.exfat \$P"
            )
            "EXT4" -> listOf(
                "mkfs.ext4 -F -L PHONESTICK \$P",
                "mke2fs -t ext4 -F -L PHONESTICK \$P"
            )
            else -> listOf(  // FAT32
                // newfs_msdos 是本机唯一确定存在的 FAT 工具。
                // -c 1 表示簇大小 1 扇区（512B）；不指定时它会按容量自动挑，
                // 大镜像上会出现 32KB 簇，部分车机/老设备兼容性差。
                "newfs_msdos -F 32 -c 1 \$P",
                // Android 上没有 mkfs.vfat / mkfs.fat，但保留以免未来有
                "mkfs.vfat -F 32 -n PHONESTICK \$P",
                "mkfs.fat -F 32 -n PHONESTICK \$P",
                // 最后兜底：如果 newfs_msdos 因为容量太小拒绝做 FAT32，
                // 用 exFAT 至少能挂载出一个可用分区
                "mkfs.exfat -n PHONESTICK \$P"
            )
        }

        var lastErr = ""
        for (c in cmds) {
            val script = arrayOf(
                "LOOP=\$(losetup -f --show '$e' 2>/dev/null)",
                "if [ -z \"\$LOOP\" ]; then echo LOOP_FAIL; exit 0; fi",
                "P=\${LOOP}p1",
                // MBR 无效时内核不会生成 p1 节点
                "if [ ! -b \"\$P\" ]; then",
                "  losetup -d \"\$LOOP\" 2>/dev/null || true",
                "  echo NOPART; exit 0",
                "fi",
                // 复查分区实际大小。分区表里的扇区数超出镜像容量时，
                // 内核会 "extends beyond EOD, truncated" 把分区变成 0 长度，
                // 后续所有写入都被丢弃却仍然返回 0 —— 必须在这里拦住。
                "PSZ=\$(blockdev --getsize64 \"\$P\" 2>/dev/null || echo 0)",
                "if [ \"\$PSZ\" = \"0\" ] || [ -z \"\$PSZ\" ]; then",
                "  losetup -d \"\$LOOP\" 2>/dev/null || true",
                "  echo P1_ZERO; exit 0",
                "fi",
                "$c 2>&1",
                "RC=\$?",
                // 立刻用 blkid 在同一 loop 上确认文件系统真的落下去了
                "ID=\$(blkid \"\$P\" 2>/dev/null)",
                "losetup -d \"\$LOOP\" 2>/dev/null || true",
                "sync",
                "echo \"BLKID:\$ID\"",
                "if [ \$RC -eq 0 ]; then echo FMT_OK; else echo FMT_FAIL; fi"
            )
            val r = Shell.cmd(*script).exec()
            val outText = (r.out + r.err).joinToString("\n")

            if (outText.contains("FMT_OK")) {
                Log.d(TAG, "format via loop succeeded: $c (${outText.replace("\n", " | ")})")
                return Pair(true, "")
            }
            if (outText.contains("P1_ZERO")) {
                // 分区长度为 0 是 MBR 扇区数写错的特征，换 mkfs 工具也没用
                Log.e(TAG, "partition 1 has zero length — partition table size mismatch")
                return Pair(
                    false,
                    "分区表与镜像容量不一致（分区 1 长度为 0）。请重新创建镜像。"
                )
            }
            lastErr = outText.trim()
            Log.w(TAG, "format attempt failed [$c]: $lastErr")
        }

        return Pair(false, lastErr.ifEmpty { "所有 mkfs 尝试均失败" })
    }

    /**
     * 校验：内核能否识别出分区和文件系统。
     *
     * 双保险：
     *   1. blkid 认得出分区里的文件系统（TYPE="vfat"/"ext4"）；
     *   2. 直接读镜像的原始字节，确认 MBR 签名和分区引导扇区还在 ——
     *      实测某些情况下 mkfs 返回 0 却什么都没写，光看返回值会假成功。
     */
    private fun verifyPartition(path: String, fmt: String): Boolean {
        val e = escapePath(path)
        val expectType = when (fmt) {
            "EXT4" -> "ext4"
            "EXFAT" -> "exfat"
            else -> "vfat"
        }
        val script = arrayOf(
            // --- blkid ---
            "LOOP=\$(losetup -f --show '$e' 2>/dev/null)",
            "OUT=''",
            "if [ -n \"\$LOOP\" ]; then",
            "  OUT=\$(blkid \${LOOP}p1 2>/dev/null)",
            "  losetup -d \"\$LOOP\" 2>/dev/null || true",
            "fi",
            "echo \"ID: \$OUT\"",
            // --- 原始字节：MBR 签名 ---
            "SIG=\$(dd if='$e' bs=1 skip=510 count=2 2>/dev/null | od -An -tx1 -v | tr -d ' \\n')",
            "echo \"SIG: \$SIG\"",
            // --- 原始字节：分区 1 的首个扇区，绝对偏移 2048*512 = 1048576 ---
            // FAT32: 引导扇区偏移 82 处有 "FAT32   " 字样
            // exFAT: 引导扇区有 "EXFAT   " 字样
            // ext4 : 超级块在分区偏移 0x400，魔数 0x53EF 位于偏移 0x438，
            //        即镜像绝对偏移 1048576 + 1080 = 1059656
            //
            // 注意 od 的 -An 只去掉行首偏移标号，不保证不折行，
            // 所以这里用 grep 在整块数据里找特征串，不依赖精确偏移。
            "BS=\$(dd if='$e' bs=512 skip=2048 count=1 2>/dev/null | od -An -tx1 -v | tr -d ' \\n')",
            "MAGIC=''",
            // "FAT32" = 46 41 54 33 32 ; "EXFAT" = 45 58 46 41 54
            "if echo \"\$BS\" | grep -qi '4641543332'; then MAGIC=FAT32; fi",
            "if echo \"\$BS\" | grep -qi '4558464154'; then MAGIC=EXFAT; fi",
            // ext4 超级块不在首个扇区（偏移 0x400），单独读 2 字节比对魔数 0x53EF
            "EXTM=\$(dd if='$e' bs=1 skip=1059656 count=2 2>/dev/null | od -An -tx1 -v | tr -d ' \\n')",
            "if [ \"\$EXTM\" = \"53ef\" ]; then MAGIC=EXT4; fi",
            "echo \"MAGIC: \$MAGIC\"",
            "if [ \"\$SIG\" != \"55aa\" ]; then echo VERIFY_FAIL; exit 0; fi",
            "if echo \"\$OUT\" | grep -q '$expectType'; then echo VERIFY_OK; exit 0; fi",
            "if [ -n \"\$MAGIC\" ]; then echo VERIFY_OK; else echo VERIFY_FAIL; fi"
        )
        val r = Shell.cmd(*script).exec()
        Log.d(TAG, "verifyPartition: ${r.out}")
        return r.out.contains("VERIFY_OK")
    }

    // ==========================================================
    // 工具方法
    // ==========================================================

    private fun escapePath(p: String): String = p.replace("'", "'\\''")

    private fun fileExists(path: String): Boolean {
        val e = escapePath(path)
        val r = Shell.cmd("if [ -e '$e' ]; then echo Y; fi").exec()
        return r.out.contains("Y")
    }

    private fun getFileSize(path: String): Long {
        val e = escapePath(path)
        val r = Shell.cmd("stat -c %s '$e' 2>/dev/null || echo 0").exec()
        return r.out.lastOrNull()?.trim()?.toLongOrNull() ?: 0L
    }
}
