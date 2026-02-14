package org.fossify.voicerecorder.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.voicerecorder.databinding.ItemServerBinding
import org.fossify.voicerecorder.models.Server

class ServerAdapter(private val servers: MutableList<Server>) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(servers[position], position)
    }

    override fun getItemCount() = servers.size

    inner class ServerViewHolder(private val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(server: Server, position: Int) {
            binding.serverIndex.text = "${position + 1}."
            binding.serverName.text = server.name
            binding.serverIp.text = server.ip
            binding.connectButton.text = if (server.isConnected) "Disconnect" else "Connect"
        }
    }
}
