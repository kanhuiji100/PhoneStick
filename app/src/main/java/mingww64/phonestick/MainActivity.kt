package mingww64.phonestick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedImagePath: String = ""
    private var isCurrentlyMounted: Boolean = false

    /** 缓存的大小，来自 root shell 的 stat，避免 UI 线程做 IO */
    private var selectedSizeBytes: Long = 0L

    /** 缓存的实际占用（稀疏镜像 < 逻辑大小） */
    private var selectedUsedBytes: Long = 0L

    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = result.data?.getStringExtra("path") ?: ""
            val autoMount = result.data?.getBooleanExtra("auto_mount", false) ?: false

            Log.d("MainActivity", "selectImageLauncher path=$path, autoMount=$autoMount")

            if (path.isNotEmpty()) {
                selectedImagePath = path
                selectedSizeBytes = 0L
                selectedUsedBytes = 0L
                saveSelectedImagePath(path)
                refreshStatus()

                if (autoMount) {
                    mountSelectedImage()
                }
            } else {
                Snackbar.make(binding.root, R.string.file_picker_nofile, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved image path
        selectedImagePath = getSharedPreferences("phonestick", MODE_PRIVATE)
            .getString("last_image_path", "") ?: ""

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupListeners() {
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_licenses -> {
                    startActivity(Intent(this, LicenseActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.btnSelectImage.setOnClickListener {
            openImageChooser()
        }

        binding.cardSelectedImage.setOnClickListener {
            openImageChooser()
        }

        binding.btnMount.setOnClickListener {
            if (selectedImagePath.isEmpty()) {
                Snackbar.make(binding.root, R.string.file_picker_nofile, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mountSelectedImage()
        }

        binding.btnUnmount.setOnClickListener {
            unmountGadget()
        }
    }

    private fun openImageChooser() {
        val intent = Intent(this, ImageChooserActivity::class.java).apply {
            putExtra("selected_path", selectedImagePath)
        }
        selectImageLauncher.launch(intent)
    }

    private fun refreshStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()

            // 用 root shell 判断文件是否还在。
            // 不能用 File.exists()：/sdcard 是 FUSE 视图，由 root 创建的镜像
            // 属主是 root:everybody(9997)、权限 rw-rw----，app uid 读不到，
            // File.exists() 会误报 false，导致选中项被清空。
            var path = selectedImagePath
            var size = selectedSizeBytes
            var used = 0L

            if (path.isNotEmpty() && !path.startsWith("content://")) {
                val info = ImageStore.query(path)
                if (!info.exists) {
                    path = ""
                    size = 0L
                    used = 0L
                    saveSelectedImagePath("")
                } else {
                    size = info.size
                    used = info.usedBytes
                    // 已挂载但还没选中时，用内核里记录的路径反填
                    if (path.isEmpty() && status.isMounted) {
                        path = ImageStore.toUserPath(status.currentFile)
                        saveSelectedImagePath(path)
                    }
                }
            } else if (path.isEmpty() && status.isMounted && status.currentFile.isNotEmpty()) {
                path = ImageStore.toUserPath(status.currentFile)
                saveSelectedImagePath(path)
            }

            withContext(Dispatchers.Main) {
                selectedImagePath = path
                selectedSizeBytes = size
                selectedUsedBytes = used
                isCurrentlyMounted = status.isMounted

                if (status.isMounted) {
                    binding.tvStatusBadge.text = getString(R.string.status_mounted)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_mounted))
                    binding.tvLunInfo.text = getString(R.string.status_lun_path, status.lunPath)
                } else {
                    binding.tvStatusBadge.text = getString(R.string.status_unmounted)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_unmounted))
                    binding.tvLunInfo.text = getString(R.string.status_lun_path, "None")
                }
                updateUi()
            }
        }
    }

    private fun updateUi() {
        val hasSelectedImage = selectedImagePath.isNotEmpty()

        if (hasSelectedImage) {
            if (selectedImagePath.startsWith("content://")) {
                val decoded = Uri.decode(selectedImagePath)
                var rawName = decoded.substringAfterLast('/').substringAfterLast("%3F").substringAfterLast('?')
                if (rawName.contains(':')) {
                    rawName = rawName.substringAfterLast(':')
                }
                binding.tvSelectedFileName.text = if (rawName.isNotBlank()) rawName else "导入的文档"
                binding.tvSelectedPath.text = decoded
            } else {
                val file = File(selectedImagePath)
                binding.tvSelectedFileName.text = file.name
                // 大小已经在 IO 线程查好并缓存，UI 线程不再做 shell 查询
                val sizeStr = ImageStore.formatSizeWithUsage(selectedSizeBytes, selectedUsedBytes)
                val dir = ImageStore.toUserPath(file.parent ?: "")
                binding.tvSelectedPath.text = "$sizeStr • $dir"
            }
        } else {
            binding.tvSelectedFileName.text = getString(R.string.file_picker_nofile)
            binding.tvSelectedPath.text = ""
        }

        updateFadedState(isMounted = isCurrentlyMounted, hasSelectedImage = hasSelectedImage)
    }

    private fun updateFadedState(isMounted: Boolean, hasSelectedImage: Boolean) {
        val mountEnabled = hasSelectedImage && !isMounted
        val unmountEnabled = isMounted
        val settingsEnabled = hasSelectedImage && !isMounted

        setElementState(binding.btnMount, mountEnabled)
        setElementState(binding.btnUnmount, unmountEnabled)
        setElementState(binding.switchReadOnly, settingsEnabled)
        setElementState(binding.switchCdrom, settingsEnabled)
    }

    private fun setElementState(view: View, isEnabled: Boolean) {
        view.isEnabled = isEnabled
        val targetAlpha = if (isEnabled) 1.0f else 0.4f
        view.animate().alpha(targetAlpha).setDuration(200).start()
    }

    private fun mountSelectedImage() {
        val readOnly = binding.switchReadOnly.isChecked
        val cdrom = binding.switchCdrom.isChecked

        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val (success, message) = UsbGadgetController.mountImage(this@MainActivity, selectedImagePath, readOnly, cdrom)
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (success) {
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("挂载失败")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                refreshStatus()
            }
        }
    }

    private fun unmountGadget() {
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val (success, message) = UsbGadgetController.unmountImage(this@MainActivity)
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (success) {
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("卸载失败")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                refreshStatus()
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.progressIndicator.visibility = View.VISIBLE
            binding.btnMount.isEnabled = false
            binding.btnUnmount.isEnabled = false
            binding.btnSelectImage.isEnabled = false
        } else {
            binding.progressIndicator.visibility = View.GONE
            updateFadedState(isCurrentlyMounted, selectedImagePath.isNotEmpty())
            binding.btnSelectImage.isEnabled = true
        }
    }

    private fun saveSelectedImagePath(path: String) {
        getSharedPreferences("phonestick", MODE_PRIVATE)
            .edit()
            .putString("last_image_path", path)
            .apply()
    }
}
