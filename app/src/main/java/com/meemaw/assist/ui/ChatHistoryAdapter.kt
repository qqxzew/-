package com.meemaw.assist.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.meemaw.assist.databinding.ItemChatHistoryEntryBinding

data class ChatHistoryEntry(
    val sessionId: String,
    val title: String,
    val preview: String,
    val isCurrent: Boolean
)

class ChatHistoryAdapter(
    private val onClick: (ChatHistoryEntry) -> Unit
) : ListAdapter<ChatHistoryEntry, ChatHistoryAdapter.HistoryViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatHistoryEntry>() {
            override fun areItemsTheSame(oldItem: ChatHistoryEntry, newItem: ChatHistoryEntry): Boolean {
                return oldItem.sessionId == newItem.sessionId
            }

            override fun areContentsTheSame(oldItem: ChatHistoryEntry, newItem: ChatHistoryEntry): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return HistoryViewHolder(ItemChatHistoryEntryBinding.inflate(inflater, parent, false), onClick)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HistoryViewHolder(
        private val binding: ItemChatHistoryEntryBinding,
        private val onClick: (ChatHistoryEntry) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatHistoryEntry) {
            binding.tvTitle.text = item.title
            binding.tvPreview.text = item.preview
            binding.root.alpha = if (item.isCurrent) 1f else 0.78f
            binding.root.setOnClickListener {
                onClick(item)
            }
        }
    }
}