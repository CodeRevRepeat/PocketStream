package com.pocketstream.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pocketstream.protocol.MediaItem
import com.pocketstream.tv.databinding.ItemTvMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class TvMediaAdapter(
    private var items: List<MediaItem>,
    private var phoneBaseUrl: String = "",
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<TvMediaAdapter.TvMediaViewHolder>() {

    private val httpClient = OkHttpClient()
    private val bitmapCache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun updateList(newItems: List<MediaItem>, baseUrl: String = phoneBaseUrl) {
        items = newItems
        phoneBaseUrl = baseUrl
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

            val thumbUrl = if (phoneBaseUrl.isNotEmpty() && item.thumbnailPath.isNotEmpty()) {
                "$phoneBaseUrl${item.thumbnailPath}"
            } else {
                ""
            }

            binding.ivTvMediaThumbnail.setImageBitmap(null)
            binding.ivTvMediaPlaceholder.visibility = View.VISIBLE

            if (thumbUrl.isNotEmpty()) {
                val cached = bitmapCache.get(thumbUrl)
                if (cached != null) {
                    binding.ivTvMediaThumbnail.setImageBitmap(cached)
                    binding.ivTvMediaPlaceholder.visibility = View.GONE
                } else {
                    binding.ivTvMediaThumbnail.tag = thumbUrl
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val req = Request.Builder().url(thumbUrl).build()
                            val resp = httpClient.newCall(req).execute()
                            if (resp.isSuccessful) {
                                val bytes = resp.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bmp != null) {
                                        bitmapCache.put(thumbUrl, bmp)
                                        withContext(Dispatchers.Main) {
                                            if (binding.ivTvMediaThumbnail.tag == thumbUrl) {
                                                binding.ivTvMediaThumbnail.setImageBitmap(bmp)
                                                binding.ivTvMediaPlaceholder.visibility = View.GONE
                                            }
                                        }
                                    }
                                }
                            }
                            resp.close()
                        } catch (_: Exception) {}
                    }
                }
            }

            binding.cardContainer.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
