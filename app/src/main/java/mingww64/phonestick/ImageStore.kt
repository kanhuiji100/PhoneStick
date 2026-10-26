package mingww64.phonestick

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * 磁盘镜像的统一访问层。
 *
 * 为什么需要它：
 *   现代 Android 的 /sdcard 是 FUSE 视图。通过 root 或其他特权进程创建的文件，
 *   在 /sdcard 视角下属主可能变成 root:everybody(9997)，权限 rw-rw----，
 *   而 app 自身的 uid（如 10451）既不是属主也不在该组，other 位又没有权限，
 *   于是 Java 层的 File.length() / File.exists() / File.canRead() 全部返回 0 / false。
 *
 *   更麻烦的是：如果文件在 app 自己的 filesDir 下（app_data_file），app 读得到，
 *   但内核的 USB gadget 线程（u:r:kernel:s0）写不进去，表现为主机 fdisk 报 I/O error。
 *
 *   所以正确的存放位置是 /sdcard/PhoneStick/ 对应的 /data/media/0/PhoneStick/，
 *   标签为 media_rw_data_file —— 内核可写、app 可通过 root shell 读。
 *
 *   所有对镜像的元数据查询都走 root shell 的 stat，不依赖 Java File API。
 */
object ImageStore {

    private const val TAG = "ImageStore"

    /** /sdcard 下的镜像统一目录 */
    const val EXTERNAL_DIR = "/sdcard/PhoneStick"

    /** 对应的真实路径（绕过 FUSE） */
    const val RAW_DIR = "/data/media/0/PhoneStick"

    data class Info(
        val path: String,
        val exists: Boolean,
        val isFile: Boolean,
        val size: Long,
        /** stat %b：已分配的 512 字节块数。稀疏镜像会明显小于 size/512 */
        val allocatedBlocks: Long = 0L
    ) {
        val valid: Boolean get() = exists && isFile && size > 0

        /** 实际占用字节数（稀疏镜像小于 size） */
        val usedBytes: Long get() = allocatedBlocks * 512L

        /** 是否稀疏：实际占用明显小于逻辑大小 */
        /**
         * 是否稀疏：实际占用小于逻辑容量。
         *
         * 注意「0 占用也算稀疏」——刚建好的裸盘镜像一个块都没分配，
         * 正是最彻底的稀疏；早期的判断写成 usedBytes > 0，把这种情况漏掉了，
         * 导致裸盘镜像既不显示占用、也没有「释放未使用空间」菜单项。
         */
        val isSparse: Boolean get() = valid && usedBytes < size
    }

    private fun escape(path: String): String = path.replace("'", "'\\''")

    /**
     * 把 /sdcard/... 或 /storage/emulated/... 转成 /data/media/... 原始路径。
     * gadget 绑定和 stat 都优先用原始路径，避免 FUSE 层的权限与性能问题。
     *
     * 注意最后两条：旧版本 2.3.0 会在 prefs 里留下形如 `/media/0/PhoneStick/x.img`
     * 的脏路径（丢了 `/data` 前缀，/media 目录根本不存在）。这个值会被
     * 原样写进 LUN 的 file 节点，内核不报错但指向空气，表现为主机端
     * 认盘却所有读写都失败。这里统一归一化，避免脏数据继续传染。
     */
    fun toRawPath(path: String): String = when {
        path.startsWith("/data/media/") -> path
        path.startsWith("/sdcard/") -> "/data/media/0/" + path.removePrefix("/sdcard/")
        path.startsWith("/storage/emulated/0/") ->
            "/data/media/0/" + path.removePrefix("/storage/emulated/0/")
        path.startsWith("/mnt/pass_through/0/emulated/0/") ->
            "/data/media/0/" + path.removePrefix("/mnt/pass_through/0/emulated/0/")
        // 旧版脏路径：/media/0/... → /data/media/0/...
        path.startsWith("/media/0/") -> "/data/media/0/" + path.removePrefix("/media/0/")
        path.startsWith("/media/") -> "/data/media/0/" + path.removePrefix("/media/")
        else -> path
    }

    /** 反向：raw 路径转成用户可见的 /sdcard 路径 */
    fun toUserPath(path: String): String = when {
        path.startsWith("/data/media/0/") -> "/sdcard/" + path.removePrefix("/data/media/0/")
        else -> path
    }

    /**
     * 判断一个路径是否属于本应用管理的镜像目录。
     * 用于拒绝把 LUN 绑到任意位置（例如误绑到 /media/0 这种不存在的目录）。
     */
    fun isManagedPath(path: String): Boolean {
        val raw = toRawPath(path)
        return raw.startsWith("$RAW_DIR/")
    }

    /**
     * 通过 root shell 批量查询文件信息。
     * 返回 map: 原始 path -> Info
     *
     * 用 shell 而不是 Java File API 的原因见类注释。这里把每条命令拆成
     * 独立数组元素，让 libsu 逐条执行，避免整段拼接时的引号地狱。
     */
    fun queryAll(paths: Collection<String>): Map<String, Info> {
        if (paths.isEmpty()) return emptyMap()

        val unique = paths.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return emptyMap()

        val builder = Shell.cmd()
        for (p in unique) {
            val raw = toRawPath(p)
            val e = escape(raw)
            // 每行输出: <原路径>|EX|ISF|SIZE|BLOCKS
            // 用 stat 的 %s 拿逻辑大小、%b 拿已分配块数（512B 单位）。
            // 稀疏镜像的 %b 远小于 %s，UI 用它显示"实际占用"。
            builder.add("P='$e'")
            builder.add("EX=0; ISF=0; SZ=0; BL=0")
            builder.add("if [ -e \"\$P\" ]; then EX=1; fi")
            builder.add("if [ -f \"\$P\" ]; then ISF=1; fi")
            builder.add("if [ \"\$EX\" = \"1\" ]; then SZ=\$(stat -c %s \"\$P\" 2>/dev/null); BL=\$(stat -c %b \"\$P\" 2>/dev/null); fi")
            builder.add("echo \"$e|\$EX|\$ISF|\${SZ:-0}|\${BL:-0}\"")
        }

        val result = try {
            builder.exec()
        } catch (e: Exception) {
            Log.e(TAG, "queryAll failed: ${e.message}", e)
            return emptyMap()
        }

        val map = mutableMapOf<String, Info>()
        for (line in result.out) {
            // 路径本身可能含 '|'，所以从右侧切分：末尾四个字段是固定格式
            val parts = line.split('|')
            if (parts.size < 5) continue
            val blocksStr = parts.last().trim()
            val sizeStr = parts[parts.size - 2].trim()
            val isFileStr = parts[parts.size - 3].trim()
            val existsStr = parts[parts.size - 4].trim()
            val p = parts.dropLast(4).joinToString("|")
            if (p.isBlank()) continue

            val size = sizeStr.toLongOrNull() ?: 0L
            val blocks = blocksStr.toLongOrNull() ?: 0L
            val info = Info(
                path = p,
                exists = existsStr == "1",
                isFile = isFileStr == "1",
                size = size,
                allocatedBlocks = blocks
            )
            map[p] = info
            // 同时按用户路径登记一份，方便调用方用任意形式查找
            map[toUserPath(p)] = info
        }
        return map
    }

    fun query(path: String): Info {
        val raw = toRawPath(path)
        return queryAll(listOf(raw))[raw]
            ?: Info(raw, exists = false, isFile = false, size = 0L)
    }

    /**
     * 确保镜像所在目录存在，并修正属主，使 app 与内核两侧都能正常访问。
     * 目录用 media_rw(1023) 属主、setgid 模式下 2770，符合 Android 共享存储惯例；
     * setgid 让目录里新建的文件自动继承 media_rw 组。
     */
    fun ensureDir(): Boolean {
        val res = Shell.cmd(
            "mkdir -p '$RAW_DIR'",
            "chown 1023:1023 '$RAW_DIR' 2>/dev/null || true",
            "chmod 2770 '$RAW_DIR' 2>/dev/null || true",
            "[ -d '$RAW_DIR' ] && echo DIR_OK"
        ).exec()
        return res.out.contains("DIR_OK")
    }

    /**
     * 修正镜像文件的属主与权限。
     *
     * 目标是让三方都能访问：
     *   - app 自身（uid 如 10451）：靠 other 读位
     *   - 内核 gadget 线程（u:r:kernel:s0）：靠属主/other 写位
     *   - MediaProvider / FUSE：靠 media_rw 组
     *
     * 0664 + media_rw 组就能同时满足，且不会过度开放。
     */
    fun makeReadable(path: String) {
        val e = escape(toRawPath(path))
        Shell.cmd(
            "chown 1023:1023 '$e' 2>/dev/null || true",
            "chmod 0664 '$e' 2>/dev/null || true"
        ).exec()
    }

    /**
     * 列出目录下的镜像文件。
     * 用 shell 的 find 而不是 File.listFiles()，避免 FUSE 权限问题。
     */
    fun listImages(): List<File> {
        val res = Shell.cmd(
            "for f in '$RAW_DIR'/*.img '$RAW_DIR'/*.iso '$RAW_DIR'/*.bin '$RAW_DIR'/*.raw '$RAW_DIR'/*.vhd '$RAW_DIR'/*.qcow2; do",
            "  [ -f \"\$f\" ] && echo \"\$f\"",
            "done 2>/dev/null"
        ).exec()

        return res.out
            .map { it.trim() }
            .filter { it.startsWith("/data/media/") && it.contains('.') }
            .distinct()
            .map { File(it) }
    }

    /**
     * 让 /sdcard（FUSE）视角和 MediaStore 记录跟上真实文件大小。
     *
     * 问题：我们用 root 直接在 /data/media/0 下 truncate/重建镜像，
     * MediaProvider 完全不知道。如果这个路径之前有记录（例如同名旧镜像），
     * FUSE 会一直返回旧大小 —— 实测 4GB 的镜像在文件管理器里永远显示 2GB，
     * 而且 MediaProvider 不重启就不会刷新。
     *
     * 办法：拿真实大小，通过 FUSE 路径（/sdcard/...）做一次同长度 truncate。
     * 这个调用会经过 MediaProvider，它会把记录和属性缓存一起更新。
     */
    fun syncFuseMetadata(path: String) {
        val raw = toRawPath(path)
        val user = toUserPath(raw)
        if (!user.startsWith("/sdcard/")) return
        val size = query(raw).size
        if (size <= 0) return
        Shell.cmd("truncate -s $size '" + escape(user) + "' 2>/dev/null || true").exec()
    }

    /**
     * 批量修复：FUSE 视图大小与实际不符的，按实际大小重设一次。
     * 用于列表加载时自愈历史遗留的脏记录（升级前创建的镜像都可能有）。
     */
    fun repairFuseMetadata(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val cmds = mutableListOf<String>()
        for (p in paths) {
            val raw = toRawPath(p)
            val user = toUserPath(raw)
            if (!user.startsWith("/sdcard/")) continue
            val eRaw = escape(raw)
            val eUser = escape(user)
            cmds += "RS=\$(stat -c %s '" + eRaw + "' 2>/dev/null || echo 0)"
            cmds += "US=\$(stat -c %s '" + eUser + "' 2>/dev/null || echo 0)"
            cmds += "if [ \"\$RS\" != \"0\" ] && [ \"\$RS\" != \"\$US\" ]; then " +
                "truncate -s \"\$RS\" '" + eUser + "' 2>/dev/null || true; fi"
        }
        if (cmds.isEmpty()) return
        try {
            Shell.cmd(*cmds.toTypedArray()).exec()
        } catch (e: Exception) {
            Log.w(TAG, "repairFuseMetadata failed: ${e.message}")
        }
    }

    fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "—"
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$sizeBytes B"
        }
    }

    /**
     * 容量 + 实际占用：
     *   - 稀疏且已写入：`8.00 GB · 已用 264 MB`
     *   - 稀疏但一个块都没分配（典型：刚建好的裸盘）：`2.00 GB · 未写入`
     *   - 占用接近逻辑大小（预分配/已写满）：只显示容量，避免 `4.00 GB · 已用 4.00 GB`
     */
    fun formatSizeWithUsage(sizeBytes: Long, usedBytes: Long): String {
        val base = formatSize(sizeBytes)
        if (sizeBytes <= 0) return base
        if (usedBytes <= 0) return "$base · 未写入"
        // 占用 >= 逻辑大小的 95% 视为预分配/已写满，没必要重复显示
        if (usedBytes >= sizeBytes * 95 / 100) return base
        return "$base · 已用 ${formatSize(usedBytes)}"
    }

    /** 稀疏判定（供 UI 层复用）：实际占用小于逻辑容量即为稀疏，0 占用也算 */
    fun isSparse(sizeBytes: Long, usedBytes: Long): Boolean =
        sizeBytes > 0 && usedBytes < sizeBytes
}
