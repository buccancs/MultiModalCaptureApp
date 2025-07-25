package com.multimodal.capture.ui.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.multimodal.capture.ui.fragments.DeviceManagementFragment
import com.multimodal.capture.ui.fragments.MainCaptureFragment
import com.multimodal.capture.ui.fragments.SessionsFragment

/**
 * FragmentStateAdapter for the main ViewPager2 navigation.
 * Manages the primary sections of the app: Devices, Capture, and Sessions.
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    companion object {
        const val TAB_DEVICES = 0
        const val TAB_CAPTURE = 1
        const val TAB_SESSIONS = 2
        const val TAB_COUNT = 3
    }

    override fun getItemCount(): Int = TAB_COUNT

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            TAB_DEVICES -> DeviceManagementFragment()
            TAB_CAPTURE -> MainCaptureFragment()
            TAB_SESSIONS -> SessionsFragment() // Use the new SessionsFragment
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }
}