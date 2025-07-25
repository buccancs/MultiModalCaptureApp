package com.multimodal.capture.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.multimodal.capture.data.SessionFolder
import com.multimodal.capture.databinding.FragmentSessionsBinding
import com.multimodal.capture.ui.SessionFolderActivity
import com.multimodal.capture.ui.adapter.SessionFolderAdapter
import com.multimodal.capture.utils.SessionFolderManager
import timber.log.Timber

/**
 * Fragment for browsing and managing recorded data sessions.
 */
class SessionsFragment : Fragment() {

    private var _binding: FragmentSessionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionFolderManager: SessionFolderManager
    private lateinit var sessionFolderAdapter: SessionFolderAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadSessionFolders()
    }

    private fun setupRecyclerView() {
        sessionFolderManager = SessionFolderManager(requireContext())
        sessionFolderAdapter = SessionFolderAdapter { sessionFolder ->
            // Navigate to a detail view for the session
            val intent = Intent(requireContext(), SessionFolderActivity::class.java).apply {
                putExtra("session_path", sessionFolder.path)
            }
            startActivity(intent)
        }

        binding.recyclerViewSessions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sessionFolderAdapter
        }
    }

    private fun loadSessionFolders() {
        try {
            val sessions = sessionFolderManager.getSessionFolders()
            sessionFolderAdapter.updateSessions(sessions)
            binding.textViewEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            Timber.d("Loaded ${sessions.size} session folders.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load session folders")
            binding.textViewEmpty.text = "Error loading sessions."
            binding.textViewEmpty.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}