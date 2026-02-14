package org.fossify.voicerecorder.fragments

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.voicerecorder.adapters.ServerAdapter
import org.fossify.voicerecorder.databinding.FragmentNetworkBinding
import org.fossify.voicerecorder.models.Server

class NetworkFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet) {
    private lateinit var binding: FragmentNetworkBinding
    private lateinit var serverAdapter: ServerAdapter
    private val servers = mutableListOf<Server>()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentNetworkBinding.bind(this)

        serverAdapter = ServerAdapter(servers)
        binding.serversList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = serverAdapter
        }

        updateServers()
    }

    private fun updateServers() {
        if (servers.isEmpty()) {
            binding.noServersFound.visibility = View.VISIBLE
            binding.serversList.visibility = View.GONE
        } else {
            binding.noServersFound.visibility = View.GONE
            binding.serversList.visibility = View.VISIBLE
        }
        serverAdapter.notifyDataSetChanged()
    }

    override fun onResume() {}

    override fun onDestroy() {}

    fun finishActMode() {}

    fun onSearchTextChanged(text: String) {}
}
