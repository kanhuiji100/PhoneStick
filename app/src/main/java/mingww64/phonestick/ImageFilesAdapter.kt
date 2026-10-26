package mingww64.phonestick

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mingww64.phonestick.databinding.ImageChooserRowBinding
import java.io.File

class ImageFilesAdapter(
    private val files: MutableList<File>,
    private val selectedPath: String,
    private val sizeProvider: (String) -> Long,
    private val usedProvider: (String) -> Long = { 0L },
    private val onFileSelected: (File) -> Unit,
    private val onFileMount: (File) -> Unit,
    private val onFileDeleted: (File) -> Unit,
    private val onFileRenamed: (File, File) -> Unit,
    private val onFileCompact: (File) -> Unit = {}
) : RecyclerView.Adapter<ImageFilesAdapter.ViewHolder>() {

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) checkedFiles.clear()
            notifyDataSetChanged()
        }

    val checkedFiles = mutableSetOf<File>()
    var onSelectionCountChanged: (Int) -> Unit = {}
    var onFileLongClick: ((File) -> Unit)? = null

    inner class ViewHolder(val binding: ImageChooserRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ImageChooserRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val context = holder.itemView.context

        holder.binding.filename.text = file.name
        // 不能直接用 file.length()：/sdcard 是 FUSE 视图，由 root 创建的镜像
        // app 无读权限，length() 会返回 0，列表里全部显示 "0 B"
        // 稀疏镜像额外显示实际占用："8.00 GB · 264 MB used"
        holder.binding.fileSize.text = ImageStore.formatSizeWithUsage(
            sizeProvider(file.absolutePath),
            usedProvider(file.absolutePath)
        )

        if (file.extension.equals("iso", ignoreCase = true)) {
            holder.binding.ivFileIcon.setImageResource(R.drawable.ic_disc_vector)
        } else {
            holder.binding.ivFileIcon.setImageResource(R.drawable.ic_sd_storage_vector)
        }

        val isSelected = file.absolutePath == selectedPath

        // Set active box outline stroke and active badge
        if (isSelected) {
            holder.binding.cardItem.strokeWidth = dpToPx(context, 2)
            holder.binding.tvSelectedBadge.visibility = View.VISIBLE
        } else {
            holder.binding.cardItem.strokeWidth = 0
            holder.binding.tvSelectedBadge.visibility = View.GONE
        }

        if (isSelectionMode) {
            holder.binding.cbSelect.visibility = View.VISIBLE
            holder.binding.cbSelect.isChecked = checkedFiles.contains(file)
            holder.binding.btnOverflow.visibility = View.GONE

            holder.itemView.setOnClickListener {
                if (checkedFiles.contains(file)) {
                    checkedFiles.remove(file)
                } else {
                    checkedFiles.add(file)
                }
                notifyItemChanged(position)
                onSelectionCountChanged(checkedFiles.size)
            }
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.binding.cbSelect.visibility = View.GONE
            holder.binding.btnOverflow.visibility = View.VISIBLE

            holder.itemView.setOnClickListener {
                onFileSelected(file)
            }

            holder.itemView.setOnLongClickListener {
                onFileLongClick?.invoke(file)
                true
            }

            holder.binding.btnOverflow.setOnClickListener { view ->
                showPopupMenu(context, view, file)
            }
        }
    }

    private fun showPopupMenu(context: Context, anchorView: View, file: File) {
        val popup = PopupMenu(context, anchorView)
        popup.menu.add("立即挂载")

        // 只有稀疏镜像（实际占用小于逻辑容量，含 0 占用的裸盘）才提供空间回收
        val size = sizeProvider(file.absolutePath)
        val used = usedProvider(file.absolutePath)
        if (ImageStore.isSparse(size, used)) {
            popup.menu.add(context.getString(R.string.image_reclaim_space))
        }

        popup.menu.add("重命名")
        popup.menu.add("删除")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "立即挂载" -> {
                    onFileMount(file)
                    true
                }
                context.getString(R.string.image_reclaim_space) -> {
                    onFileCompact(file)
                    true
                }
                "重命名" -> {
                    showRenameDialog(context, file)
                    true
                }
                "删除" -> {
                    showDeleteDialog(context, file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(context: Context, file: File) {
        val input = EditText(context)
        input.setText(file.name)
        input.setSelection(file.name.lastIndexOf('.').let { if (it > 0) it else file.name.length })

        MaterialAlertDialogBuilder(context)
            .setTitle("重命名镜像")
            .setView(input)
            .setPositiveButton("重命名") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    // 用 shell 重命名：镜像属主可能是 root/media_rw，
                    // app 的 File.renameTo() 会因权限失败。
                    // 走 /sdcard（FUSE）路径，MediaProvider 才会跟着改记录，
                    // 不然新名字在文件管理器里查不到、旧记录还留着。
                    val rawOld = ImageStore.toRawPath(file.absolutePath)
                    val rawNew = ImageStore.toRawPath(File(file.parentFile, newName).absolutePath)
                    val userOld = ImageStore.toUserPath(rawOld)
                    val userNew = ImageStore.toUserPath(rawNew)
                    val useFuse = userOld.startsWith("/sdcard/") && userNew.startsWith("/sdcard/")
                    val eOld = (if (useFuse) userOld else rawOld).replace("'", "'\\''")
                    val eNew = (if (useFuse) userNew else rawNew).replace("'", "'\\''")
                    val r = com.topjohnwu.superuser.Shell.cmd(
                        "if [ ! -e '$eNew' ]; then mv -f '$eOld' '$eNew' && echo RENAMED; fi",
                        if (useFuse) "rm -f '" + rawOld.replace("'", "'\\''") + "' 2>/dev/null || true" else "true"
                    ).exec()
                    if (r.out.contains("RENAMED")) {
                        val newFile = File(file.parentFile, newName)
                        ImageStore.makeReadable(newFile.absolutePath)
                        onFileRenamed(file, newFile)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(context: Context, file: File) {
        val isSelected = file.absolutePath == selectedPath
        val msg = if (isSelected) {
            "“${file.name}”正在使用中。删除会先卸载 U 盘，再删除该文件。是否继续？"
        } else {
            "确定要删除“${file.name}”吗？"
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("删除镜像")
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ ->
                onFileDeleted(file)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun getItemCount(): Int = files.size

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}