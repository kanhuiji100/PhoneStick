package mingww64.phonestick

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 释放镜像里「已删除数据」占用的空间。
 *
 * 背景：
 *   稀疏镜像只按写入量占空间，但主机删除文件时，文件系统只是把块标记为空闲，
 *   镜像文件里的那些块依然被占用，手机存储不会释放。要真正回收必须打洞
 *   （fallocate PUNCH_HOLE），而设备自带的 toybox fallocate 只有 -l/-o，
 *   没有打洞能力；写零反而会重新分配块，越写越占。
 *
 * 做法：
 *   随包带一个静态编译的 aarch64 小工具 `pstrim`（tools/pstrim.c），
 *   运行时释放到 /data/local/tmp/pstrim，以 root 执行。它按文件系统
 *   读取「空闲块表」，再把空闲区间按 4K 对齐打洞：
 *     - FAT32 : FAT 表项 == 0
 *     - exFAT : Allocation Bitmap 簇位图
 *     - NTFS  : $Bitmap（MFT 记录 6 的数据流）
 *     - ext4  : 各组 block bitmap（BLOCK_UNINIT 的组保守跳过）
 *   全程不重写数据，不需要额外空间。
 */
object ImageCompactor {
    private const val TAG = "ImageCompactor"
    private const val TMP_BIN = "/data/local/tmp/pstrim"

    data class Result(
        val success: Boolean,
        val message: String = "",
        val freedBytes: Long = 0L,
        val usedBytes: Long = 0L
    )

    private fun escape(p: String): String = p.replace("'", "'\\''")

    /** 把 assets 里的 pstrim 释放到 /data/local/tmp 并加执行位（root 侧操作） */
    private fun ensureBinary(context: Context): Boolean {
        val bytes = try {
            context.assets.open("pstrim").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "read asset failed: ${e.message}", e)
            return false
        }
        if (bytes.isEmpty()) return false

        // 已存在且大小一致就复用，省掉每次拷贝
        val sizeRes = Shell.cmd("[ -x '$TMP_BIN' ] && stat -c %s '$TMP_BIN'").exec()
        val remoteSize = sizeRes.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        if (remoteSize == bytes.size.toLong()) return true

        return try {
            val tmp = File(context.cacheDir, "pstrim")
            tmp.outputStream().use { it.write(bytes) }
            val r = Shell.cmd(
                "cp -f '${escape(tmp.absolutePath)}' '$TMP_BIN'",
                "chmod 755 '$TMP_BIN'",
                "[ -x '$TMP_BIN' ] && echo BIN_OK"
            ).exec()
            r.out.contains("BIN_OK")
        } catch (e: Exception) {
            Log.e(TAG, "extract binary failed: ${e.message}", e)
            false
        }
    }

    /**
     * 对镜像执行空间回收。必须在 IO 线程调用，且镜像不能正被 LUN 占用。
     */
    fun trim(context: Context, path: String): Result {
        val raw = ImageStore.toRawPath(path)
        val beforeInfo = ImageStore.query(raw)
        if (!beforeInfo.valid) return Result(false, "镜像不存在或为空")
        val before = beforeInfo.usedBytes

        // 一个块都没分配（典型：刚建好的裸盘）——没什么可回收的，直接返回成功
        if (before <= 0) return Result(true, "", 0L, 0L)

        if (!ensureBinary(context)) return Result(false, "无法准备整理工具（pstrim）")

        val e = escape(raw)
        // pstrim 会自己 fsync，这里再 sync 一次保证 du/stat 看到最新值
        val res = Shell.cmd(
            "'$TMP_BIN' '$e' 2>&1",
            "sync"
        ).exec()
        val out = (res.out + res.err).joinToString("\n")
        Log.d(TAG, "pstrim output: $out")

        val statusLine = out.lineSequence().firstOrNull { it.contains("STATUS=") }
            ?: return Result(false, "整理工具无输出")

        if (!statusLine.contains("STATUS=OK")) {
            val reason = Regex("reason=(\\S+)").find(statusLine)?.groupValues?.get(1) ?: statusLine
            val msg = when (reason) {
                "unknown_fs" ->
                    "无法识别镜像里的文件系统。\n\n" +
                        "目前支持 FAT32、exFAT、NTFS、ext4。裸盘镜像请先在电脑上分区格式化" +
                        "（或用本 App 的「格式化镜像」），再回来整理。"
                "no_mbr", "no_partition" ->
                    "该镜像还没有分区表。裸盘镜像需要先在电脑上分区格式化，再回来整理。"
                "trim_failed" ->
                    "整理失败：该文件系统的空闲块表无法解析（可能是版本差异）。\n\n" +
                        "原始信息：$statusLine"
                "open_failed" -> "无法打开镜像文件"
                "geometry" -> "镜像分区参数异常"
                else -> "整理失败：$reason"
            }
            return Result(false, msg)
        }

        val fs = Regex("fs=([a-z0-9]+)").find(statusLine)?.groupValues?.get(1) ?: ""
        Log.d(TAG, "trim ok, fs=$fs")

        val after = Regex("after=(\\d+)").find(statusLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        // 权限位可能在操作中被内核保留，这里统一修回 app / gadget 都能访问的状态
        ImageStore.makeReadable(raw)

        val freed = (before - after).coerceAtLeast(0L)
        return Result(true, "", freed, after)
    }

    /** 设备 ABI 是否支持随包的 pstrim（当前只编译了 arm64-v8a） */
    fun isSupported(): Boolean = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
}
