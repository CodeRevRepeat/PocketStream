package com.pocketstream.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pocketstream.protocol.MediaItem
import com.pocketstream.tv.databinding.ItemTvMediaBinding
import java.util.Locale

class TvMediaAdapter(
    private var items: List<MediaItem>,
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<TvMediaAdapter.TvMediaViewHolder>() {

    fun updateList(newItems: List<MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TvMediaViewHolder {
        val binding = ItemTvMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TvMediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TvMediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class TvMediaViewHolder(private val binding: ItemTvMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.tvTvMediaTitle.text = item.title

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

            binding.tvTvMediaBadge.text = format
            val resStr = if (item.height > 0) "${item.height}p • " else ""
            binding.tvTvMediaSubtitle.text = "$resStr$sizeStr"

            binding.cardContainer.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}