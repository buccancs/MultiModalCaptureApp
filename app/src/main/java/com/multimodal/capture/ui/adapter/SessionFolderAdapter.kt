package com.multimodal.capture.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.multimodal.capture.data.SessionFolder
import com.multimodal.capture.databinding.ItemSessionFolderBinding

/**
 * RecyclerView adapter for displaying session folders
 */
class SessionFolderAdapter(
    private val onSessionClick: (SessionFolder) -> Unit
) : RecyclerView.Adapter<SessionFolderAdapter.SessionFolderViewHolder>() {

    private var sessions = listOf<SessionFolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionFolderViewHolder {
        val binding = ItemSessionFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionFolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionFolderViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun getItemCount(): Int = sessions.size

    /**
     * Update the list of sessions with DiffUtil for efficient updates
     */
    fun updateSessions(newSessions: List<SessionFolder>) {
        val diffCallback = SessionDiffCallback(sessions, newSessions)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        
        sessions = newSessions
        diffResult.dispatchUpdatesTo(this)
    }

    inner class SessionFolderViewHolder(
        private val binding: ItemSessionFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSessionClick(sessions[position])
                }
            }
        }

        fun bind(sessionFolder: SessionFolder) {
            binding.apply {
                tvSessionName.text = sessionFolder.name
                tvSessionDate.text = sessionFolder.getFormattedDate()
                tvFileCount.text = sessionFolder.getFileCountString()
                tvSessionSize.text = sessionFolder.getFormattedSize()
            }
        }
    }

    /**
     * DiffUtil callback for efficient list updates
     */
    private class SessionDiffCallback(
        private val oldList: List<SessionFolder>,
        private val newList: List<SessionFolder>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].name == newList[newItemPosition].name
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            
            return oldItem.name == newItem.name &&
                    oldItem.fileCount == newItem.fileCount &&
                    oldItem.totalSize == newItem.totalSize &&
                    oldItem.createdDate == newItem.createdDate
        }
    }
}