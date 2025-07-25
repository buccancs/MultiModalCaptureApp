package com.multimodal.capture.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.multimodal.capture.R
import com.multimodal.capture.data.SessionFile
import com.multimodal.capture.data.SessionFileType
import com.multimodal.capture.databinding.ItemSessionFileBinding

/**
 * RecyclerView adapter for displaying session files
 */
class SessionFileAdapter(
    private val onFileClick: (SessionFile) -> Unit,
    private val onFileShare: (SessionFile) -> Unit,
    private val onFileDelete: (SessionFile) -> Unit
) : RecyclerView.Adapter<SessionFileAdapter.SessionFileViewHolder>() {

    private var files = listOf<SessionFile>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionFileViewHolder {
        val binding = ItemSessionFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SessionFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionFileViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    /**
     * Update the list of files with DiffUtil for efficient updates
     */
    fun updateFiles(newFiles: List<SessionFile>) {
        val diffCallback = SessionFileDiffCallback(files, newFiles)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        
        files = newFiles
        diffResult.dispatchUpdatesTo(this)
    }

    inner class SessionFileViewHolder(
        private val binding: ItemSessionFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onFileClick(files[position])
                }
            }
            
            binding.btnShare.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onFileShare(files[position])
                }
            }
            
            binding.btnDelete.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onFileDelete(files[position])
                }
            }
        }

        fun bind(sessionFile: SessionFile) {
            binding.apply {
                tvFileName.text = sessionFile.name
                tvFileSize.text = sessionFile.getFormattedSize()
                tvFileType.text = sessionFile.type.displayName
                
                // Set appropriate icon based on file type
                val iconRes = when (sessionFile.type) {
                    SessionFileType.AUDIO -> R.drawable.ic_audio_file
                    SessionFileType.VIDEO -> R.drawable.ic_video_file
                    SessionFileType.GSR_DATA -> R.drawable.ic_data_file
                    SessionFileType.THERMAL_YUV,
                    SessionFileType.THERMAL_ARGB,
                    SessionFileType.THERMAL_RAW,
                    SessionFileType.THERMAL_PSEUDO -> R.drawable.ic_thermal_file
                    SessionFileType.METADATA -> R.drawable.ic_json_file
                    SessionFileType.UNKNOWN -> R.drawable.ic_unknown_file
                }
                ivFileIcon.setImageResource(iconRes)
            }
        }
    }

    /**
     * DiffUtil callback for efficient list updates
     */
    private class SessionFileDiffCallback(
        private val oldList: List<SessionFile>,
        private val newList: List<SessionFile>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].path == newList[newItemPosition].path
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            
            return oldItem.name == newItem.name &&
                    oldItem.size == newItem.size &&
                    oldItem.type == newItem.type &&
                    oldItem.lastModified == newItem.lastModified
        }
    }
}