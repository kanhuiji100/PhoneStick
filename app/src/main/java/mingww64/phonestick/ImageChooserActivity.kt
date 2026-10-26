package mingww64.phonestick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.transition.TransitionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivityImageChooserBinding
import mingww64.phonestick.databinding.DialogCreateImageBinding
import java.io.File

class ImageChooserActivity : AppCompatActivity() {

    private companion object {
        const val CHANNEL_ID = "image_creation_channel"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var binding: ActivityImageChooserBinding
    private lateinit var adapter: ImageFilesAdapter
    private val imageFiles = mutableListOf<File>()
    private var isSpeedDialOpen = false
    private var currentlySelectedPath: String = ""

    /** path -> size(bytes)，来自 root shell 的 stat */
    private val fileSizes = mutableMapOf<String, Long>()

    /** path -> 实际占用(bytes)，稀疏镜像会小于 size */
    private val fileUsedSizes = mutableMapOf<String, Long>()

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not persistable
            }
            handleImportedUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageChooserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentlySelectedPath = intent.getStringExtra("selected_path") ?: ""

        binding.toolbar.setNavigationOnClickListener { finishWithResult() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFormatSelectionMode) {
                    exitFormatSelectionMode()
                } else {
                    finishWithResult()
                }
            }
        })

        setupNotificationChannel()
        setupSpeedDial()
        setupRecyclerView()
        loadImages()
    }

    private fun setupSpeedDial() {
        binding.fabMain.setOnClickListener {
            toggleSpeedDial()
        }

        binding.fabOverlay.setOnClickListener {
            if (isSpeedDialOpen) toggleSpeedDial()
        }

        binding.fabImportImage.setOnClickListener {
            toggleSpeedDial()
            val mimeTypes = arrayOf(
                "application/octet-stream",
                "application/x-iso9660-image",
                "application/x-raw-disk-image",
                "application/x-cd-image",
                "application/vnd.iso",
                "application/x-vhd",
                "application/x-qemu-disk"
            )
            importFileLauncher.launch(mimeTypes)
        }

        binding.fabCreateImage.setOnClickListener {
            toggleSpeedDial()
            showCreateImageDialog()
        }

        binding.fabFormatImage.setOnClickListener {
            toggleSpeedDial()
            showMultiFormatDialog()
        }
    }

    private fun toggleSpeedDial() {
        isSpeedDialOpen = !isSpeedDialOpen
        if (isSpeedDialOpen) {
            // Animate '+' icon rotating 135 degrees into an 'x' icon
            binding.fabMain.animate().rotation(135f).setDuration(250).start()

            binding.fabOverlay.visibility = View.VISIBLE
            binding.fabOverlay.alpha = 0f
            binding.fabOverlay.animate().alpha(1f).setDuration(200).start()

            binding.layoutImportImage.visibility = View.VISIBLE
            binding.layoutImportImage.alpha = 0f
            binding.layoutImportImage.translationY = 40f
            binding.layoutImportImage.scaleX = 0.8f
            binding.layoutImportImage.scaleY = 0.8f
            binding.layoutImportImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()

            binding.layoutCreateImage.visibility = View.VISIBLE
            binding.layoutCreateImage.alpha = 0f
            binding.layoutCreateImage.translationY = 40f
            binding.layoutCreateImage.scaleX = 0.8f
            binding.layoutCreateImage.scaleY = 0.8f
            binding.layoutCreateImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .start()

            binding.layoutFormatImage.visibility = View.VISIBLE
            binding.layoutFormatImage.alpha = 0f
            binding.layoutFormatImage.translationY = 40f
            binding.layoutFormatImage.scaleX = 0.8f
            binding.layoutFormatImage.scaleY = 0.8f
            binding.layoutFormatImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start()

        } else {
            // Animate 'x' icon rotating back 135 degrees into a '+' icon
            binding.fabMain.animate().rotation(0f).setDuration(200).start()

            binding.fabOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                binding.fabOverlay.visibility = View.GONE
            }.start()

            binding.layoutImportImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutImportImage.visibility = View.GONE
                }
                .start()

            binding.layoutCreateImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutCreateImage.visibility = View.GONE
                }
                .start()

            binding.layoutFormatImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutFormatImage.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupRecyclerView() {
        adapter = ImageFilesAdapter(
            files = imageFiles,
            selectedPath = currentlySelectedPath,
            sizeProvider = { path -> fileSizes[path] ?: 0L },
            usedProvider = { path -> fileUsedSizes[path] ?: 0L },
            onFileSelected = { file ->
                selectAndReturnFile(file.absolutePath)
            },
            onFileMount = { file ->
                selectAndReturnFile(file.absolutePath, autoMount = true)
            },
            onFileDeleted = { file ->
                handleDeleteFile(file)
            },
            onFileRenamed = { oldFile, newFile ->
                if (currentlySelectedPath == oldFile.absolutePath) {
                    currentlySelectedPath = newFile.absolutePath
                }
                loadImages()
            },
            onFileCompact = { file -> reclaimSpace(file) }
        )
        adapter.onFileLongClick = { file ->
            enterFormatSelectionMode(initialSelectedFile = file)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun handleDeleteFile(file: File) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()
            val isMountedImage = status.isMounted && (status.currentFile == file.absolutePath || currentlySelectedPath == file.absolutePath)
            
            if (isMountedImage) {
                UsbGadgetController.unmountImage(this@ImageChooserActivity)
            }
            
            removeExternalImagePath(file.absolutePath)
            // 用 shell 删除：镜像属主可能是 root/media_rw，app 的 File.delete() 会失败。
            // 优先走 /sdcard（FUSE）路径：MediaProvider 会同步删掉自己的记录，
            // 否则文件管理器里会残留一条指向已删除文件的「幽灵」条目。
            val raw = ImageStore.toRawPath(file.absolutePath)
            val user = ImageStore.toUserPath(raw)
            val eRaw = raw.replace("'", "'\\''")
            val eUser = user.replace("'", "'\\''")
            if (user.startsWith("/sdcard/")) {
                com.topjohnwu.superuser.Shell.cmd(
                    "rm -f '$eUser' 2>/dev/null",
                    "rm -f '$eRaw' 2>/dev/null"
                ).exec()
            } else {
                com.topjohnwu.superuser.Shell.cmd("rm -f '$eRaw'").exec()
            }

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (currentlySelectedPath == file.absolutePath) {
                    currentlySelectedPath = ""
                }
                loadImages()
                val msg = if (isMountedImage) "已卸载并删除 ${file.name}" else "已删除 ${file.name}"
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }


    /**
     * 释放稀疏镜像里已删除文件占用的空间（打洞，不重写数据）。
     *
     * 前置条件：镜像不能正被 LUN 占用，否则打洞会把主机正在读写的块挖掉。
     */
    private fun reclaimSpace(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()
            val raw = ImageStore.toRawPath(file.absolutePath)
            val mounted = status.isMounted && (
                ImageStore.toRawPath(status.currentFile) == raw ||
                    ImageStore.toRawPath(currentlySelectedPath) == raw
                )

            withContext(Dispatchers.Main) {
                if (mounted) {
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle(getString(R.string.image_reclaim_space))
                        .setMessage(getString(R.string.image_reclaim_unmount_first))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@withContext
                }
                if (!ImageCompactor.isSupported()) {
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle(getString(R.string.image_reclaim_space))
                        .setMessage(getString(R.string.image_reclaim_failed, "当前设备架构不支持"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@withContext
                }

                val dialog = MaterialAlertDialogBuilder(this@ImageChooserActivity)
                    .setTitle(getString(R.string.image_reclaim_space))
                    .setMessage(getString(R.string.image_reclaim_running))
                    .setCancelable(false)
                    .create()
                dialog.show()

                lifecycleScope.launch(Dispatchers.IO) {
                    val result = ImageCompactor.trim(this@ImageChooserActivity, file.absolutePath)
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        if (result.success) {
                            loadImages()
                            val msg = if (result.freedBytes <= 0) {
                                getString(R.string.image_reclaim_none)
                            } else {
                                getString(
                                    R.string.image_reclaim_done,
                                    ImageStore.formatSize(result.freedBytes),
                                    ImageStore.formatSize(result.usedBytes)
                                )
                            }
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                        } else {
                            MaterialAlertDialogBuilder(this@ImageChooserActivity)
                                .setTitle(getString(R.string.image_reclaim_space))
                                .setMessage(getString(R.string.image_reclaim_failed, result.message))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun loadImages() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 统一从 /sdcard/PhoneStick 目录枚举镜像。
            // 不用 filesDir.listFiles()：那个位置是 app_data_file，
            // 内核 gadget 线程写不进去，会导致主机端 fdisk 报 I/O error。
            ImageStore.ensureDir()
            val externalFiles = ImageStore.listImages()

            // 自愈：历史镜像可能因为「root 重建同名文件」而让 MediaStore
            // 留着旧大小（文件管理器显示 2GB 的元凶）。列表加载时对一遍，
            // 不一致的按真实大小重设一次。
            ImageStore.repairFuseMetadata(externalFiles.map { it.absolutePath })

            val sizeMap = ImageStore.queryAll(externalFiles.map { it.absolutePath })

            val combined = externalFiles.distinctBy { it.absolutePath }

            withContext(Dispatchers.Main) {
                fileSizes.clear()
                fileUsedSizes.clear()
                for ((k, v) in sizeMap) {
                    fileSizes[k] = v.size
                    fileUsedSizes[k] = v.usedBytes
                }
                imageFiles.clear()
                imageFiles.addAll(combined)
                if (::adapter.isInitialized) {
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun handleImportedUri(uri: Uri) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            // 解析出用户选的源文件路径
            val resolvedPath = UriPathResolver.getRealPathFromUri(this@ImageChooserActivity, uri)

            // 关键改动：不再对源路径做"能不能读"的校验就放弃，
            // 而是统一复制到 /sdcard/PhoneStick/（media_rw_data_file）。
            //
            // 原实现直接拿 resolvedPath 去 stat/校验，遇到用户从"文档"或
            // 其他 SAF provider 选的、权限受限的文件时会得到 size=0，
            // 于是提示 "0 bytes empty" 并拒绝导入；即使通过了，
            // 文件也可能留在 app_data_file 位置，导致内核写不进去。
            val imported = if (resolvedPath.isNotEmpty() && !resolvedPath.startsWith("content://")) {
                importFromPath(resolvedPath)
            } else {
                importFromUri(uri)
            }

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (imported != null) {
                    val info = ImageStore.query(imported)
                    if (info.valid) {
                        // 选中并直接返回主界面，主界面才能更新状态、启用 Mount 按钮
                        Snackbar.make(
                            binding.root,
                            "已导入 ${File(imported).name}（${ImageStore.formatSize(info.size)}）",
                            Snackbar.LENGTH_SHORT
                        ).show()
                        currentlySelectedPath = imported
                        selectAndReturnFile(imported)
                    } else {
                        showImportError("导入的文件为空或无法读取")
                    }
                } else {
                    showImportError("导入所选文件失败")
                }
            }
        }
    }

    private fun showImportError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("导入失败")
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 把普通文件路径导入到共享目录。
     * 若已经在 /sdcard/PhoneStick 下则直接返回；否则复制过去。
     * 返回可用的镜像路径，失败返回 null。
     */
    private fun importFromPath(srcPath: String): String? {
        val rawSrc = ImageStore.toRawPath(srcPath)
        val name = File(srcPath).name.let {
            if (isValidImageExtension(File(it).extension)) it else "$it.img"
        }
        val dst = "${ImageStore.RAW_DIR}/$name"

        val eSrc = srcPath.replace("'", "'\\''")
        val eRaw = rawSrc.replace("'", "'\\''")
        val eDst = dst.replace("'", "'\\''")

        // 已在目标目录就直接用
        if (rawSrc == dst && ImageStore.query(dst).valid) {
            ImageStore.makeReadable(dst)
            return ImageStore.toUserPath(dst)
        }

        val res = com.topjohnwu.superuser.Shell.cmd(
            "mkdir -p '${ImageStore.RAW_DIR}'",
            "SRC='$eSrc'",
            "RAW='$eRaw'",
            "DST='$eDst'",
            // 源文件存在且非空才复制
            "if [ ! -s \"\$RAW\" ]; then echo SRC_EMPTY; exit 0; fi",
            // 目标已存在且大小一致，视为已导入
            "SSZ=\$(stat -c %s \"\$RAW\" 2>/dev/null || echo 0)",
            "DSZ=\$(stat -c %s \"\$DST\" 2>/dev/null || echo 0)",
            "if [ \"\$SSZ\" = \"\$DSZ\" ] && [ \"\$DSZ\" != \"0\" ]; then echo DUP_OK; exit 0; fi",
            "cp -f \"\$RAW\" \"\$DST\" 2>&1 || cp -f \"\$SRC\" \"\$DST\" 2>&1",
            "sync",
            "chown 1023:1023 \"\$DST\" 2>/dev/null || true",
            "chmod 0664 \"\$DST\" 2>/dev/null || true",
            "OSZ=\$(stat -c %s \"\$DST\" 2>/dev/null || echo 0)",
            "if [ \"\$OSZ\" = \"\$SSZ\" ]; then echo COPY_OK; else echo COPY_FAIL; fi"
        ).exec()

        val out = (res.out + res.err).joinToString("\n")
        android.util.Log.d("ImageChooserActivity", "importFromPath out=$out")

        return when {
            out.contains("COPY_OK") || out.contains("DUP_OK") -> {
                // 同名旧镜像可能残留 MediaStore 记录，导入后同步一次大小
                ImageStore.syncFuseMetadata(dst)
                ImageStore.toUserPath(dst)
            }
            else -> null
        }
    }

    /**
     * 从 SAF uri 直接流式复制到共享目录。
     * 用于源路径无法通过 root 拿到实体文件的情况。
     */
    private fun importFromUri(uri: Uri): String? {
        var name = Uri.decode(uri.toString()).substringAfterLast('/').substringAfterLast("%3F").substringAfterLast('?')
        if (name.contains(':')) name = name.substringAfterLast(':')
        if (name.isBlank()) name = "imported_${System.currentTimeMillis()}.img"
        if (!isValidImageExtension(File(name).extension)) name = "$name.img"

        val dstPath = "${ImageStore.RAW_DIR}/$name"
        val tmpPath = "$dstPath.tmp"

        try {
            val tmpFile = File(tmpPath)
            contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
        } catch (e: Exception) {
            android.util.Log.e("ImageChooserActivity", "copy from uri failed: ${e.message}", e)
            com.topjohnwu.superuser.Shell.cmd("rm -f '${tmpPath.replace("'", "'\\''")}'").exec()
            return null
        }

        val eTmp = tmpPath.replace("'", "'\\''")
        val eDst = dstPath.replace("'", "'\\''")
        val res = com.topjohnwu.superuser.Shell.cmd(
            "TSZ=\$(stat -c %s '$eTmp' 2>/dev/null || echo 0)",
            "if [ \"\$TSZ\" = \"0\" ]; then rm -f '$eTmp'; echo EMPTY; exit 0; fi",
            "mv -f '$eTmp' '$eDst'",
            "sync",
            "chown 1023:1023 '$eDst' 2>/dev/null || true",
            "chmod 0664 '$eDst' 2>/dev/null || true",
            "echo MOVED"
        ).exec()

        if (res.out.contains("MOVED")) {
            ImageStore.syncFuseMetadata(dstPath)
            return ImageStore.toUserPath(dstPath)
        }
        return null
    }

    private fun isValidImageExtension(ext: String): Boolean {
        return ext.equals("img", ignoreCase = true) ||
                ext.equals("iso", ignoreCase = true) ||
                ext.equals("bin", ignoreCase = true) ||
                ext.equals("raw", ignoreCase = true) ||
                ext.equals("vhd", ignoreCase = true) ||
                ext.equals("qcow2", ignoreCase = true)
    }

    private fun getExternalImagePaths(): Set<String> {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        return prefs.getStringSet("external_images", emptySet()) ?: emptySet()
    }

    private fun addExternalImagePath(path: String) {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("external_images", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(path)
        prefs.edit().putStringSet("external_images", current).apply()
    }

    private fun removeExternalImagePath(path: String) {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("external_images", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(path)
        prefs.edit().putStringSet("external_images", current).apply()
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission granted or denied
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "镜像创建"
            val descriptionText = "Progress notifications for image creation"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showImageCreationNotification(
        fileName: String,
        percent: Int,
        writtenMb: Long,
        totalMb: Long,
        isDone: Boolean = false,
        isError: Boolean = false,
        errorMsg: String = ""
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sd_storage_vector)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isError) {
            builder.setContentTitle("创建镜像失败：$fileName")
                .setContentText(errorMsg)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(false)
                .setProgress(0, 0, false)
        } else if (isDone) {
            builder.setContentTitle("镜像创建成功")
                .setContentText("$fileName（$totalMb MB）已创建。")
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(false)
                .setProgress(0, 0, false)
        } else {
            val contentText = if (writtenMb > 0 || totalMb > 0) "$writtenMb MB / $totalMb MB" else "正在创建镜像…"
            builder.setContentTitle("正在创建镜像：$fileName")
                .setContentText(contentText)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, percent, false)
                .setOngoing(true)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun showCreateImageDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val dialogBinding = DialogCreateImageBinding.inflate(layoutInflater)

        // Handle shortcut chip selection
        dialogBinding.chipGroupSize.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val sizeMb = when (checkedIds.first()) {
                    R.id.chip512m -> 512
                    R.id.chip1g -> 1024
                    R.id.chip2g -> 2048
                    R.id.chip4g -> 4096
                    R.id.chip8g -> 8192
                    else -> 1024
                }
                if (dialogBinding.etImageSizeMb.text.toString() != sizeMb.toString()) {
                    dialogBinding.etImageSizeMb.setText(sizeMb.toString())
                }
            }
        }

        // Unselect chip shortcut button when custom specified size box is focused
        dialogBinding.etImageSizeMb.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                dialogBinding.chipGroupSize.clearCheck()
            }
        }

        // Unselect chip shortcut button when typing custom specified size
        dialogBinding.etImageSizeMb.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (dialogBinding.etImageSizeMb.hasFocus() && dialogBinding.chipGroupSize.checkedChipId != View.NO_ID) {
                    dialogBinding.chipGroupSize.clearCheck()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.create_image_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        // Override positive button click handler to prevent auto-dismissal while creation is running
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val name = dialogBinding.etImageName.text.toString().trim()
            val sizeStr = dialogBinding.etImageSizeMb.text.toString().trim()
            val sizeMb = sizeStr.toLongOrNull() ?: 1024L

            if (name.isEmpty()) {
                dialogBinding.etImageName.error = "Name cannot be empty"
                return@setOnClickListener
            }

            // Extract selected filesystem format
            val fsFormat = when (dialogBinding.chipGroupFs.checkedChipId) {
                R.id.chipFsExt4 -> "EXT4"
                R.id.chipFsExfat -> "EXFAT"
                R.id.chipFsNone -> "NONE"
                else -> "FAT32"
            }
            // 稀疏镜像：默认勾选。truncate 秒建，物理占用随主机写入增长。
            val sparse = dialogBinding.switchFastAllocation.isChecked

            // Disable controls during creation
            dialogBinding.etImageName.isEnabled = false
            dialogBinding.etImageSizeMb.isEnabled = false
            dialogBinding.chipGroupSize.isEnabled = false
            dialogBinding.chipGroupFs.isEnabled = false
            dialogBinding.switchFastAllocation.isEnabled = false
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = false
            dialog.setCancelable(false)

            dialogBinding.layoutProgress.visibility = View.VISIBLE
            dialogBinding.progressIndicatorDialog.isIndeterminate = false
            dialogBinding.progressIndicatorDialog.progress = 0
            dialogBinding.tvProgressStatus.text = "正在初始化镜像创建…"

            val cleanName = if (name.endsWith(".img", ignoreCase = true) || name.endsWith(".iso", ignoreCase = true)) {
                name
            } else {
                "$name.img"
            }

            showImageCreationNotification(cleanName, 0, 0, sizeMb)

            // 目标目录改为 /data/media/0/PhoneStick（标签 media_rw_data_file）。
            // 原来的 filesDir 是 app_data_file，SELinux 不允许内核 gadget 线程
            // 写入，会导致主机端 fdisk 写分区表报 I/O error。
            ImageStore.ensureDir()

            ImageCreator.createBlankImage(
                targetDirectory = File(ImageStore.RAW_DIR),
                fileName = name,
                sizeInMB = sizeMb,
                filesystemFormat = fsFormat,
                sparse = sparse,
                useFastAllocation = !sparse,
                onProgressStatus = { status, percent, writtenMb, totalMb ->
                    dialogBinding.progressIndicatorDialog.progress = percent
                    dialogBinding.tvProgressStatus.text = status
                    showImageCreationNotification(cleanName, percent, writtenMb, totalMb)
                },
                onComplete = { success, msg, file ->
                    dialog.dismiss()
                    if (success && file != null) {
                        showImageCreationNotification(cleanName, 100, sizeMb, sizeMb, isDone = true)
                        val userPath = ImageStore.toUserPath(file.absolutePath)
                        currentlySelectedPath = userPath
                        loadImages()
                        // 创建完成后直接返回主界面，省去用户再点一次
                        selectAndReturnFile(userPath)
                    } else {
                        showImageCreationNotification(cleanName, 0, 0, sizeMb, isError = true, errorMsg = msg)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("创建镜像失败")
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            )
        }
    }

    private var isFormatSelectionMode = false

    private fun showMultiFormatDialog() {
        if (imageFiles.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("没有镜像文件")
                .setMessage("没有可格式化的镜像文件。")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        enterFormatSelectionMode()
    }

    private fun enterFormatSelectionMode(initialSelectedFile: File? = null) {
        TransitionManager.beginDelayedTransition(binding.root)
        isFormatSelectionMode = true
        adapter.isSelectionMode = true
        if (initialSelectedFile != null) {
            adapter.checkedFiles.add(initialSelectedFile)
            adapter.notifyDataSetChanged()
        }
        binding.speedDialContainer.visibility = View.GONE

        val initialCount = adapter.checkedFiles.size
        binding.toolbar.title = if (initialCount > 0) "Selected $initialCount Image(s)" else "Select Image(s)"
        binding.toolbar.setNavigationIcon(R.drawable.ic_close_vector)
        binding.toolbar.setNavigationOnClickListener {
            exitFormatSelectionMode()
        }

        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_format)
        binding.toolbar.setOnMenuItemClickListener { item ->
            val selected = adapter.checkedFiles.toList()
            when (item.itemId) {
                R.id.action_confirm_format -> {
                    if (selected.isEmpty()) {
                        Snackbar.make(binding.root, "请至少选择 1 个要格式化的镜像", Snackbar.LENGTH_SHORT).show()
                    } else {
                        showFormatSelectionDialog(selected)
                    }
                    true
                }
                R.id.action_confirm_delete -> {
                    if (selected.isEmpty()) {
                        Snackbar.make(binding.root, "请至少选择 1 个要删除的镜像", Snackbar.LENGTH_SHORT).show()
                    } else {
                        confirmBatchDelete(selected)
                    }
                    true
                }
                else -> false
            }
        }

        adapter.onSelectionCountChanged = { count ->
            binding.toolbar.title = if (count > 0) "Selected $count Image(s)" else "Select Image(s)"
        }
    }

    private fun confirmBatchDelete(selectedFiles: List<File>) {
        val count = selectedFiles.size
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 $count 个镜像")
            .setMessage("确定要删除选中的 $count 个镜像文件吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                executeBatchDelete(selectedFiles)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeBatchDelete(selectedFiles: List<File>) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val mountStatus = UsbGadgetController.getMountStatus()
            val mountedPath = if (mountStatus.isMounted) mountStatus.currentFile else ""

            for (file in selectedFiles) {
                if (file.absolutePath == mountedPath || file.absolutePath == currentlySelectedPath) {
                    UsbGadgetController.unmountImage(this@ImageChooserActivity)
                    if (currentlySelectedPath == file.absolutePath) {
                        currentlySelectedPath = ""
                    }
                }
                removeExternalImagePath(file.absolutePath)
                val e = file.absolutePath.replace("'", "'\\''")
                com.topjohnwu.superuser.Shell.cmd("rm -f '$e'").exec()
            }

            withContext(Dispatchers.Main) {
                setLoading(false)
                exitFormatSelectionMode()
                loadImages()
                Snackbar.make(binding.root, "已删除 ${selectedFiles.size} 个镜像文件", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun exitFormatSelectionMode() {
        TransitionManager.beginDelayedTransition(binding.root)
        isFormatSelectionMode = false
        adapter.isSelectionMode = false
        binding.speedDialContainer.visibility = View.VISIBLE

        binding.toolbar.title = getString(R.string.title_activity_image_chooser)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back_vector)
        binding.toolbar.setNavigationOnClickListener { finishWithResult() }
        binding.toolbar.menu.clear()
    }

    private fun showFormatSelectionDialog(selectedFiles: List<File>) {
        val formats = arrayOf("FAT32", "ext4", "exFAT")
        var selectedFormatIndex = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("选择文件系统格式")
            .setSingleChoiceItems(formats, 0) { _, which ->
                selectedFormatIndex = which
            }
            .setPositiveButton("格式化（${selectedFiles.size} 个文件）") { _, _ ->
                val format = formats[selectedFormatIndex]
                executeBatchFormatting(selectedFiles, format)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeBatchFormatting(selectedFiles: List<File>, format: String) {
        setLoading(true)
        val errors = mutableListOf<String>()
        var progressSnackbar: Snackbar? = null

        lifecycleScope.launch(Dispatchers.IO) {
            for ((index, file) in selectedFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    progressSnackbar?.dismiss()
                    progressSnackbar = Snackbar.make(
                        binding.root,
                        "Formatting ${index + 1}/${selectedFiles.size}: ${file.name}...",
                        Snackbar.LENGTH_INDEFINITE
                    )
                    progressSnackbar?.show()
                }

                val (success, message) = suspendFormatImage(file, format)
                if (!success) {
                    errors.add("${file.name}: $message")
                }
            }

            withContext(Dispatchers.Main) {
                progressSnackbar?.dismiss()
                setLoading(false)
                exitFormatSelectionMode()
                loadImages()

                if (errors.isEmpty()) {
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle("格式化完成")
                        .setMessage("已成功将 ${selectedFiles.size} 个文件格式化为 $format。")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle("格式化命令错误")
                        .setMessage("已完成，但存在错误：\n\n" + errors.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private suspend fun suspendFormatImage(file: File, format: String): Pair<Boolean, String> =
        suspendCancellableCoroutine { continuation ->
            ImageCreator.formatExistingImage(file, format, onProgressStatus = {}) { success, msg ->
                if (continuation.isActive) {
                    continuation.resume(Pair(success, msg))
                }
            }
        }

    private fun setLoading(isLoading: Boolean) {
        binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun selectAndReturnFile(path: String, autoMount: Boolean = false) {
        val result = Intent().apply {
            putExtra("path", path)
            putExtra("auto_mount", autoMount)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishWithResult() {
        val result = Intent().apply {
            putExtra("path", currentlySelectedPath)
            putExtra("auto_mount", false)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

}
