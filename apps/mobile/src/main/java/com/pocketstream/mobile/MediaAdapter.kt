package com.pocketstream.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pocketstream.mobile.databinding.ItemMediaBinding
import com.pocketstream.protocol.MediaItem
import java.util.Locale

class MediaAdapter(
    private var items: List<MediaItem>,
    private val onStreamClick: (MediaItem) -> Unit,
    private val onStopStreamClick: (MediaItem) -> Unit = {},
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    var isSelectionMode: Boolean = false
        private set

    var activeStreamingId: String? = null
        private set

    fun setActiveStreamingId(id: String?) {
        activeStreamingId = id
        notifyDataSetChanged()
    }

    private val selectedItemIds = mutableSetOf<String>()

    fun updateList(newItems: List<MediaItem>) {
        items = newItems
        val existingIds = newItems.map { it.id }.toSet()
        selectedItemIds.retainAll(existingIds)
        notifyDataSetChanged()
        onSelectionChanged(selectedItemIds.size)
    }

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (!enabled) {
            selectedItemIds.clear()
            onSelectionChanged(0)
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        if (selectedItemIds.size == items.size) {
            selectedItemIds.clear()
        } else {
            selectedItemIds.clear()
            selectedItemIds.addAll(items.map { it.id })
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedItemIds.size)
    }

    fun getSelectedIds(): Set<String> = selectedItemIds.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.tvMediaTitle.text = item.title

            val sizeMb = item.sizeBytes / (1024 * 1024.0)
            val sizeStr = if (sizeMb >= 1024) {
                String.format(Locale.US, "%.1f GB", sizeMb / 1024.0)
            } else {
                String.format(Locale.US, "%.0f MB", sizeMb)
            }

            val format = when {
                item.title.endsWith(".mkv", ignoreCase = true) -> "MKV"
                item.title.endsWith(".mp4", ignoreCase = true) -> "MP4"
                item.title.endsWith(".webm", ignoreCase = true) -> "WEBM"
                else -> item.mimeType.substringAfter("video/", "VIDEO").uppercase()
            }

            val resStr = if (item.height > 0) "${item.height}p • " else ""
            binding.tvMediaSubtitle.text = "$resStr$format • $sizeStr"

            val thumbFile = com.pocketstream.mobile.storage.MediaRepository.getThumbnailFile(binding.root.context, item.id)
            if (thumbFile.exists() && thumbFile.length() > 0) {
                val bmp = android.graphics.BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (bmp != null) {
                    binding.imgMediaIcon.setImageBitmap(bmp)
                    binding.imgMediaIcon.background = null
                    binding.imgMediaIcon.setPadding(0, 0, 0, 0)
                    binding.imgMediaIcon.imageTintList = null
                    binding.imgMediaIcon.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    binding.imgMediaIcon.clipToOutline = true
                } else {
                    resetDefaultIcon()
                }
            } else {
                resetDefaultIcon()
            }

            if (isSelectionMode) {
                binding.cbSelect.visibility = View.VISIBLE
                binding.btnStreamToTv.visibility = View.GONE
                val isChecked = selectedItemIds.contains(item.id)
                binding.cbSelect.isChecked = isChecked

                binding.cardMediaRoot.setOnClickListener {
                    toggleItem(item.id)
                }
                binding.cbSelect.setOnClickListener {
                    toggleItem(item.id)
                }
            } else {
                binding.cbSelect.visibility = View.GONE
                binding.btnStreamToTv.visibility = View.VISIBLE

                val isStreaming = (item.id == activeStreamingId)
                if (isStreaming) {
                    binding.btnStreamToTv.text = "Stop Stream"
                    binding.btnStreamToTv.setTextColor(Color.WHITE)
                    binding.btnStreamToTv.strokeWidth = 0
                    binding.btnStreamToTv.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                    binding.btnStreamToTv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444")) // Red
                    binding.btnStreamToTv.setOnClickListener {
                        onStopStreamClick(item)
                    }
                    binding.cardMediaRoot.setOnClickListener {
                        onStopStreamClick(item)
                    }
                } else {
                    binding.btnStreamToTv.text = "Stream to TV"
                    binding.btnStreamToTv.setTextColor(Color.WHITE)
                    binding.btnStreamToTv.strokeWidth = 0
                    binding.btnStreamToTv.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                    binding.btnStreamToTv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#7C3AED")) // Purple
                    binding.btnStreamToTv.setOnClickListener {
                        onStreamClick(item)
                    }
                    binding.cardMediaRoot.setOnClickListener {
                        onStreamClick(item)
                    }
                }
            }
        }

        private fun resetDefaultIcon() {
            binding.imgMediaIcon.setImageResource(android.R.drawable.ic_media_play)
            binding.imgMediaIcon.setBackgroundResource(R.drawable.bg_media_badge)
            val pad = (12 * binding.root.context.resources.displayMetrics.density).toInt()
            binding.imgMediaIcon.setPadding(pad, pad, pad, pad)
            binding.imgMediaIcon.imageTintList = ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(binding.root.context, R.color.accent_cyan))
            binding.imgMediaIcon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

        private fun toggleItem(id: String) {
            if (selectedItemIds.contains(id)) {
                selectedItemIds.remove(id)
            } else {
                selectedItemIds.add(id)
            }
            notifyItemChanged(adapterPosition)
            onSelectionChanged(selectedItemIds.size)
        }
    }
}