package com.forge.bridge.ui.providers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forge.bridge.R
import com.forge.bridge.data.model.ProviderRow
import com.forge.bridge.databinding.ItemProviderBinding

class ProviderListAdapter(
    private val listener: Listener,
) : ListAdapter<ProviderRow, ProviderListAdapter.ViewHolder>(DIFF) {

    interface Listener {
        fun onConnectClick(provider: ProviderRow)
        fun onDisconnectClick(provider: ProviderRow)
        fun onTestClick(provider: ProviderRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val b: ItemProviderBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(p: ProviderRow) {
            b.tvName.text = p.name
            b.tvDescription.text = p.description
            b.tvTier.text = p.tier.uppercase()
            b.tvRequestCount.text = if (p.requestCount > 0) "${p.requestCount} req" else ""
            b.tvRequestCount.visibility = if (p.requestCount > 0) View.VISIBLE else View.GONE

            val isConnected = p.status == "connected"
            val isProxy = p.tier == "proxy"

            b.statusDot.setBackgroundResource(
                if (isConnected) R.drawable.dot_connected else R.drawable.dot_disconnected
            )
            b.tvStatus.text = if (isConnected) "Connected" else "Disconnected"
            b.tvStatus.setTextColor(b.root.context.getColor(
                if (isConnected) R.color.status_connected else R.color.status_disconnected
            ))

            b.tvDefault.visibility = if (p.isDefault) View.VISIBLE else View.GONE

            val isBrowser = p.tier == "browser"
            // Proxy + browser providers show "Login" instead of "Connect"
            // Browser providers are auto-connected by proxy login — hide Login button if already linked
            b.btnConnect.text = if (isProxy || isBrowser) "Login" else "Connect"
            b.btnConnect.visibility = if (isConnected || isBrowser) View.GONE else View.VISIBLE
            b.btnDisconnect.visibility = if (isConnected) View.VISIBLE else View.GONE
            b.btnTest.visibility = if (isConnected && !isProxy && !isBrowser) View.VISIBLE else View.GONE

            // Browser tier: show an info label explaining how it works
            if (isBrowser && !isConnected) {
                b.tvDescription.text = "Login via the proxy tier (${
                    if (p.id == "chatgpt-browser") "ChatGPT Pro/Plus" else "Claude Pro/Team"
                }) to activate this browser-tier adapter"
            }

            b.btnConnect.setOnClickListener { listener.onConnectClick(p) }
            b.btnDisconnect.setOnClickListener { listener.onDisconnectClick(p) }
            b.btnTest.setOnClickListener { listener.onTestClick(p) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProviderRow>() {
            override fun areItemsTheSame(a: ProviderRow, b: ProviderRow) = a.id == b.id
            override fun areContentsTheSame(a: ProviderRow, b: ProviderRow) = a == b
        }
    }
}
