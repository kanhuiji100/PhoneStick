package mingww64.phonestick

import android.content.Context
import android.net.Uri
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

object UsbGadgetController {
    private const val TAG = "UsbGadgetController"

    data class MountStatus(
        val isMounted: Boolean = false,
        val currentFile: String = "",
        val isReadOnly: Boolean = false,
        val isCdrom: Boolean = false,
        val lunPath: String = ""
    )

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access: ${e.message}", e)
            false
        }
    }

    /**
     * Discover the real UDC (USB Device Controller) name by listing /sys/class/udc/.
     * Qualcomm devices (including Xiaomi 15) use names like "a600000.dwc3" or "11201000.dwc3".
     * Falls back to getprop sys.usb.controller if the directory is empty.
     */
    private fun getUdcName(): String {
        val udcListRes = Shell.cmd("ls /sys/class/udc/ 2>/dev/null | head -n1").exec()
        val udcFromSysfs = udcListRes.out.firstOrNull()?.trim() ?: ""
        if (udcFromSysfs.isNotEmpty()) {
            Log.d(TAG, "Discovered UDC from /sys/class/udc/: $udcFromSysfs")
            return udcFromSysfs
        }
        val udcFromProp = Shell.cmd("getprop sys.usb.controller").exec().out.firstOrNull()?.trim() ?: ""
        Log.d(TAG, "UDC from sys.usb.controller: $udcFromProp")
        return udcFromProp
    }

    /**
     * Find the configfs mount root. Tries parsing mount output first, then falls back
     * to well-known paths used across different Android vendors (Qualcomm, MediaTek, etc.).
     */
    private fun getConfigFsPath(): String {
        val mountRes = Shell.cmd("mount -t configfs 2>/dev/null | head -n1 | awk '{print \$3}'").exec()
        val mountPath = mountRes.out.firstOrNull()?.trim() ?: ""
        if (mountPath.isNotEmpty()) {
            Log.d(TAG, "ConfigFS from mount: $mountPath")
            return mountPath
        }
        for (path in listOf("/sys/kernel/config", "/config")) {
            val res = Shell.cmd("[ -d $path/usb_gadget ] && echo OK").exec()
            if (res.out.contains("OK")) {
                Log.d(TAG, "ConfigFS found at: $path")
                return path
            }
        }
        Log.w(TAG, "ConfigFS not found")
        return ""
    }

    fun mountImage(context: Context, pathOrUri: String, readOnly: Boolean, cdrom: Boolean): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "无法获取 Root 权限")
        }

        var resolvedPath = pathOrUri
        if (pathOrUri.startsWith("content://")) {
            resolvedPath = UriPathResolver.getRealPathFromUri(context, Uri.parse(pathOrUri))
        }

        // 统一转成 /data/media 原始路径。
        // gadget 绑定 FUSE 路径（/sdcard、/storage/emulated）会带来额外的
        // 权限层与性能开销，直接用原始路径更稳。
        resolvedPath = ImageStore.toRawPath(resolvedPath)

        val escapedPath = resolvedPath.replace("'", "'\\''")
        val existsCmd = Shell.cmd(
            "if [ -f '$escapedPath' ]; then echo EXISTS; echo \"SIZE=\$(stat -c %s '$escapedPath' 2>/dev/null)\"; " +
                "else echo MISSING; fi"
        ).exec()
        if (!existsCmd.out.contains("EXISTS")) {
            return Pair(false, "镜像文件不存在：$resolvedPath")
        }
        // 空文件绑进 LUN 主机端会看到一个 0 扇区的盘，后续所有操作都会莫名失败，
        // 不如在这里明确拦住
        val size = existsCmd.out.lastOrNull { it.startsWith("SIZE=") }
            ?.removePrefix("SIZE=")?.trim()?.toLongOrNull() ?: 0L
        if (size <= 0) {
            return Pair(false, "镜像文件为空：$resolvedPath")
        }

        // 兜底：确保 kernel 侧的 gadget 线程有权限写这个文件。
        // 若镜像不在 media_rw_data_file 名下（例如残留在 app_data_file），
        // 这里会放开权限位，避免主机端写分区表报 I/O error。
        Shell.cmd(
            "chmod 0666 '$escapedPath' 2>/dev/null || true"
        ).exec()

        val roFlag = if (readOnly) "y" else "n"
        val cdromFlag = if (cdrom) "y" else "n"
        val udcName = getUdcName()
        val configFs = getConfigFsPath()
        Log.d(TAG, "Mount: UDC=$udcName configFs=$configFs")

        // --- Strategy 1: setprop sys.usb.config → wait for init to create LUN → write image ---
        // Android's init system watches sys.usb.config and automatically creates the
        // mass_storage gadget function when the property includes "mass_storage".
        // This is how DriveDroid works and is the correct approach for devices like Xiaomi 15
        // where mass_storage is not pre-present in the gadget tree.
        // If init doesn't create a LUN within 5s, the kernel doesn't support this mode
        // and we fall through directly to Strategy 2 — no redundant rescan needed.
        val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull()?.trim() ?: ""
        if (!curConfig.contains("mass_storage")) {
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)
            prefs.edit().putString("orig_usb_config", curConfig).apply()
            val targetConfig = when {
                curConfig.contains("adb") -> "mass_storage,adb"
                curConfig.isNotEmpty()    -> "mass_storage,$curConfig"
                else                      -> "mass_storage"
            }
            Log.d(TAG, "Strategy 1: setprop sys.usb.config $targetConfig (was: $curConfig)")
            Shell.cmd("setprop sys.usb.config $targetConfig").exec()
        }

        // Poll up to 5s for a mass_storage LUN to appear (created by init)
        val lunCandidates = buildList {
            if (configFs.isNotEmpty()) {
                add("$configFs/usb_gadget/g1/functions/mass_storage.0/lun.0")
                add("$configFs/usb_gadget/g1/functions/mass_storage.0/lun")
            }
            add("/sys/class/android_usb/android0/f_mass_storage/lun0")
            add("/sys/class/android_usb/android0/f_mass_storage/lun")
        }
        var foundLun = ""
        for (attempt in 1..10) {
            Thread.sleep(500)
            for (lun in lunCandidates) {
                if (Shell.cmd("[ -d '$lun' ] && echo EXISTS").exec().out.contains("EXISTS")) {
                    foundLun = lun
                    break
                }
            }
            // Also glob in case init placed it at a non-standard path
            if (foundLun.isEmpty() && configFs.isNotEmpty()) {
                foundLun = Shell.cmd(
                    "for g in $configFs/usb_gadget/*/functions/mass_storage.*/lun.*; do [ -d \"\$g\" ] && echo \"\$g\"; done 2>/dev/null"
                ).exec().out.firstOrNull { it.isNotBlank() }?.trim() ?: ""
            }
            if (foundLun.isNotEmpty()) {
                Log.d(TAG, "Strategy 1: LUN appeared at $foundLun after ${attempt * 500}ms")
                break
            }
            Log.d(TAG, "Strategy 1: waiting for LUN... attempt $attempt/10")
        }

        if (foundLun.isNotEmpty()) {
            val writeRes = Shell.cmd(
                "echo '$roFlag' > '$foundLun/ro' 2>/dev/null || true",
                "echo '$cdromFlag' > '$foundLun/cdrom' 2>/dev/null || true",
                // nofua=1 让 gadget 不再对每次写做同步落盘。
                // 在 f2fs/ext4 上，主机 fdisk 的 SYNCHRONIZE CACHE 会转成
                // fdatasync，一旦失败主机就收到 I/O error（分区表写不进去）。
                "echo 1 > '$foundLun/nofua' 2>/dev/null || true",
                "echo '$escapedPath' > '$foundLun/file'",
                "CHECK=\$(cat '$foundLun/file' 2>/dev/null)",
                "if [ -n \"\$CHECK\" ]; then echo 'S1_SUCCESS'; else echo 'S1_FAILED'; fi"
            ).exec()
            if (writeRes.out.contains("S1_SUCCESS")) {
                return Pair(true, "挂载成功（sys.usb.config，$foundLun）")
            }
            Log.w(TAG, "Strategy 1: LUN found but write failed: ${writeRes.err}")
            return Pair(false, "挂载失败：LUN 已出现，但内核拒绝了该镜像路径。\n${writeRes.err.joinToString("\n")}")
        }

        Log.w(TAG, "Strategy 1: no LUN appeared after setprop — init does not handle mass_storage on this device, trying ConfigFS gadget")


        // --- Strategy 2: Create own 'swy' gadget in ConfigFS ---
        if (configFs.isEmpty()) {
            return Pair(false, "未找到 ConfigFS")
        }
        if (udcName.isEmpty()) {
            return Pair(false, "未找到 USB 设备控制器（/sys/class/udc/ 为空且未设置 sys.usb.controller）")
        }

        val configFsScript = arrayOf(
            // Detach system g1 gadget gracefully so we can claim the UDC
            "echo '' > $configFs/usb_gadget/g1/UDC 2>/dev/null || true",
            // Detach our swy gadget if still attached from a previous session
            "echo '' > $configFs/usb_gadget/swy/UDC 2>/dev/null || true",
            "mkdir -p $configFs/usb_gadget/swy",
            "cd $configFs/usb_gadget/swy",
            "echo 0x1d6b > idVendor 2>/dev/null || true",
            "echo 0x0104 > idProduct 2>/dev/null || true",
            "echo 0x0100 > bcdUSB 2>/dev/null || true",
            "echo 0xEF > bDeviceClass 2>/dev/null || true",
            "echo 2 > bDeviceSubClass 2>/dev/null || true",
            "echo 1 > bDeviceProtocol 2>/dev/null || true",
            "mkdir -p strings/0x409 2>/dev/null || true",
            "echo 1337 > strings/0x409/serialnumber 2>/dev/null || true",
            "echo PhoneStick > strings/0x409/manufacturer 2>/dev/null || true",
            "echo 'PhoneStick Drive' > strings/0x409/product 2>/dev/null || true",
            "mkdir -p configs/swyconfig.1 2>/dev/null || true",
            "mkdir -p configs/swyconfig.1/strings/0x409 2>/dev/null || true",
            "echo 'Mass Storage' > configs/swyconfig.1/strings/0x409/configuration 2>/dev/null || true",
            "mkdir -p functions/mass_storage.0 2>/dev/null || true",
            "echo '$roFlag' > functions/mass_storage.0/lun.0/ro 2>/dev/null || true",
            "echo y > functions/mass_storage.0/lun.0/removable 2>/dev/null || true",
            "echo '$cdromFlag' > functions/mass_storage.0/lun.0/cdrom 2>/dev/null || true",
            "echo 1 > functions/mass_storage.0/lun.0/nofua 2>/dev/null || true",
            "echo '$escapedPath' > functions/mass_storage.0/lun.0/file",
            // ln -sf so re-mounting after unmount doesn't fail with "File exists"
            "ln -sf functions/mass_storage.0 configs/swyconfig.1/mass_storage.0 2>/dev/null || true",
            // Bind to the discovered UDC name (not getprop, which may be empty on Xiaomi)
            "echo '$udcName' > UDC 2>/dev/null",
            "CHECK_FILE=\$(cat functions/mass_storage.0/lun.0/file 2>/dev/null)",
            "if [ -n \"\$CHECK_FILE\" ]; then echo 'CONFIGFS_SUCCESS'; else echo 'CONFIGFS_FAILED'; fi"
        )

        val result1 = Shell.cmd(*configFsScript).exec()
        Log.d(TAG, "Strategy 2 (swy gadget) out=${result1.out} err=${result1.err}")
        if (result1.isSuccess && result1.out.contains("CONFIGFS_SUCCESS")) {
            saveAndEnableMassStorageConfig(context)
            return Pair(true, "挂载成功（ConfigFS gadget，UDC：$udcName）")
        }

        val errReason = result1.err.joinToString("\n").ifEmpty {
            result1.out.joinToString("\n").ifEmpty { "内核拒绝绑定 LUN 文件" }
        }
        return Pair(false, "挂载 USB gadget 失败：\n$errReason")
    }

    /**
     * After writing to an existing gadget's LUN, attempt to rebind its UDC
     * so the host sees a new device. On Xiaomi/Qualcomm this is often needed.
     * Errors here are non-fatal — mount already succeeded at kernel level.
     */
    private fun rebindUdc(configFs: String, udcName: String) {
        if (configFs.isEmpty() || udcName.isEmpty()) return
        try {
            val rebindScript = arrayOf(
                "for g in $configFs/usb_gadget/*/; do",
                "  CUR_UDC=\$(cat \"\${g}UDC\" 2>/dev/null | tr -d '\\n')",
                "  if [ \"\$CUR_UDC\" = '$udcName' ]; then",
                "    echo '' > \"\${g}UDC\" 2>/dev/null || true",
                "    sleep 0.1",
                "    echo '$udcName' > \"\${g}UDC\" 2>/dev/null || true",
                "    echo REBOUND",
                "    break",
                "  fi",
                "done"
            )
            val res = Shell.cmd(*rebindScript).exec()
            Log.d(TAG, "rebindUdc result: ${res.out}")
        } catch (e: Exception) {
            Log.w(TAG, "rebindUdc failed (non-fatal): ${e.message}")
        }
    }

    fun unmountImage(context: Context? = null): Pair<Boolean, String> {
        if (!isRootAvailable()) {
            return Pair(false, "Root access not available")
        }

        val configFs = getConfigFsPath()
        val udcName = getUdcName()

        val unmountScript = buildList {
            if (configFs.isNotEmpty()) {
                // Detach our own swy gadget
                add("echo '' > $configFs/usb_gadget/swy/UDC 2>/dev/null || true")
                add("if [ -d $configFs/usb_gadget/swy ]; then cd $configFs/usb_gadget/swy 2>/dev/null && rm -f configs/swyconfig.1/mass_storage.0 2>/dev/null; rmdir configs/swyconfig.1/strings/0x409 2>/dev/null; rmdir configs/swyconfig.1 2>/dev/null; rmdir functions/mass_storage.0 2>/dev/null; rmdir strings/0x409 2>/dev/null; cd ..; rmdir swy 2>/dev/null; fi || true")
                // Restore g1 gadget UDC
                if (udcName.isNotEmpty()) {
                    add("echo '$udcName' > $configFs/usb_gadget/g1/UDC 2>/dev/null || true")
                }
            }
            // Clear file from all known LUN paths using glob
            add("for lun in /config/usb_gadget/g1/functions/mass_storage.*/lun.* /sys/kernel/config/usb_gadget/g1/functions/mass_storage.*/lun.* /sys/class/android_usb/android0/f_mass_storage/lun0 /sys/class/android_usb/android0/f_mass_storage/lun; do")
            add("  [ -f \"\$lun/file\" ] && echo '' > \"\$lun/file\" 2>/dev/null || true")
            add("done")
            add("echo 'UNMOUNT_SUCCESS'")
        }.toTypedArray()

        val result = Shell.cmd(*unmountScript).exec()
        if (context != null) {
            restoreOriginalUsbConfig(context)
        } else {
            val defConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val restoredConfig = defConfig.replace("mass_storage,", "").replace(",mass_storage", "").replace("mass_storage", "none")
            Shell.cmd("setprop sys.usb.config $restoredConfig").exec()
        }

        return if (result.isSuccess) {
            Pair(true, "已卸载")
        } else {
            val errText = result.err.joinToString("\n").ifEmpty { result.out.joinToString("\n").ifEmpty { "退出码 ${result.code}" } }
            Pair(false, "卸载失败（退出码 ${result.code}）：$errText")
        }
    }

    private fun saveAndEnableMassStorageConfig(context: Context) {
        try {
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: "adb"
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)

            if (!curConfig.contains("mass_storage")) {
                prefs.edit().putString("orig_usb_config", curConfig).apply()
                val targetConfig = if (curConfig.contains("adb")) "mass_storage,adb" else "mass_storage"
                Shell.cmd("setprop sys.usb.config $targetConfig").exec()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling sys.usb.config (non-fatal): ${e.message}")
        }
    }

    private fun restoreOriginalUsbConfig(context: Context) {
        try {
            val prefs = context.getSharedPreferences("phonestick", Context.MODE_PRIVATE)
            val origConfig = prefs.getString("orig_usb_config", "") ?: ""
            val curConfig = Shell.cmd("getprop sys.usb.config").exec().out.firstOrNull() ?: ""

            val restoreTarget = when {
                origConfig.isNotEmpty() -> origConfig
                curConfig.contains("adb") -> "adb"
                else -> "none"
            }
            Log.i(TAG, "Restoring USB config to: $restoreTarget")
            Shell.cmd("setprop sys.usb.config $restoreTarget").exec()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring sys.usb.config (non-fatal): ${e.message}")
        }
    }

    fun getMountStatus(): MountStatus {
        if (!isRootAvailable()) return MountStatus()

        val configFs = getConfigFsPath()

        // Build LUN scan list dynamically from configfs, plus known fallbacks
        val checkScript = buildList {
            add("LUN_FILE=''")
            add("LUN_RO=''")
            add("LUN_CD=''")
            add("LUN_PATH=''")
            if (configFs.isNotEmpty()) {
                add("for lun in $configFs/usb_gadget/*/functions/mass_storage.*/lun.*; do")
            } else {
                add("for lun in /config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0 /sys/class/android_usb/android0/f_mass_storage/lun0; do")
            }
            add("  if [ -f \"\$lun/file\" ]; then")
            add("    CONTENT=\$(cat \"\$lun/file\" 2>/dev/null)")
            add("    if [ -n \"\$CONTENT\" ]; then")
            add("      LUN_FILE=\"\$CONTENT\"")
            add("      LUN_RO=\$(cat \"\$lun/ro\" 2>/dev/null)")
            add("      LUN_CD=\$(cat \"\$lun/cdrom\" 2>/dev/null)")
            add("      LUN_PATH=\"\$lun\"")
            add("      break")
            add("    fi")
            add("  fi")
            add("done")
            add("echo \"STATUS|\$LUN_FILE|\$LUN_RO|\$LUN_CD|\$LUN_PATH\"")
        }.toTypedArray()

        val res = Shell.cmd(*checkScript).exec()
        if (res.isSuccess) {
            val line = res.out.firstOrNull { it.startsWith("STATUS|") }
            if (line != null) {
                val parts = line.split("|")
                if (parts.size >= 5 && parts[1].isNotBlank()) {
                    return MountStatus(
                        isMounted = true,
                        currentFile = parts[1],
                        isReadOnly = parts[2] == "1" || parts[2] == "y",
                        isCdrom = parts[3] == "1" || parts[3] == "y",
                        lunPath = parts[4]
                    )
                }
            }
        }
        return MountStatus()
    }
}
