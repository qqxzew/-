package com.meemaw.assist.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.meemaw.assist.R
import com.meemaw.assist.databinding.ItemMessageAiBinding
import com.meemaw.assist.databinding.ItemMessageAiImageBinding
import com.meemaw.assist.databinding.ItemMessageUserBinding
import java.io.File

class ChatAdapter : ListAdapter<MessageItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
        private const val TYPE_SCAM = 2
        private const val TYPE_AI_IMAGE = 3

        private val DIFF = object : DiffUtil.ItemCallback<MessageItem>() {
            override fun areItemsTheSame(a: MessageItem, b: MessageItem) = a == b
            override fun areContentsTheSame(a: MessageItem, b: MessageItem) = a == b
        }
    }

    override fun getItemViewType(position: Int) = when (currentList[position]) {
        is MessageItem.User -> TYPE_USER
        is MessageItem.Ai -> TYPE_AI
        is MessageItem.ScamWarning -> TYPE_SCAM
        is MessageItem.AiImage -> TYPE_AI_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(ItemMessageUserBinding.inflate(inflater, parent, false))
            TYPE_AI_IMAGE -> AiImageVH(ItemMessageAiImageBinding.inflate(inflater, parent, false))
            else -> AiVH(ItemMessageAiBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is MessageItem.User -> (holder as UserVH).bind(item)
            is MessageItem.Ai -> (holder as AiVH).bind(item.text, false)
            is MessageItem.AiImage -> (holder as AiImageVH).bind(item)
            is MessageItem.ScamWarning -> (holder as AiVH).bind(item.text, true)
        }
    }

    class UserVH(private val b: ItemMessageUserBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MessageItem.User) {
            b.tvMessage.text = item.text
        }
    }

    class AiVH(private val b: ItemMessageAiBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(text: String, isScam: Boolean) {
            b.tvMessage.text = text
            if (isScam) {
                b.tvMessage.setBackgroundResource(R.drawable.bg_scam_warning)
                b.tvMessage.setTextColor(Color.parseColor("#CC0000"))
            } else {
                b.tvMessage.setBackgroundResource(R.drawable.bg_message_ai)
                b.tvMessage.setTextColor(Color.parseColor("#1C1C1E"))
            }
        }
    }

    class AiImageVH(private val b: ItemMessageAiImageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MessageItem.AiImage) {
            b.tvMessage.text = item.text
            val imageFile = File(item.imagePath)
            if (imageFile.exists()) {
                b.ivAnnotatedImage.visibility = View.VISIBLE
                b.ivAnnotatedImage.setImageBitmap(BitmapFactory.decodeFile(imageFile.absolutePath))
            } else {
                b.ivAnnotatedImage.visibility = View.GONE
                b.ivAnnotatedImage.setImageDrawable(null)
            }
        }
    }
}
