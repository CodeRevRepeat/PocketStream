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
                    binding.btnStreamToTv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444")) // Red
                    binding.btnStreamToTv.setOnClickListener {
                        onStopStreamClick(item)
                    }
                    binding.cardMediaRoot.setOnClickListener {
                        onStopStreamClick(item)
                    }
                } else {
                    binding.btnStreamToTv.text = "Stream to TV"
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