package org.helix.gateway.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.helix.gateway.R
import org.helix.gateway.data.Provider
import java.util.Collections

/**
 * One capsule per provider. The list owns the click and the drag, while the pill itself is painted
 * by [onBindRow] so each skin can keep its own surface without a second layout per theme.
 */
class ProviderAdapter(
    private val onClick: (Provider) -> Unit,
    private val onMore: (provider: Provider, anchor: View) -> Unit,
    private val onDragHandle: (holder: RecyclerView.ViewHolder) -> Unit,
    private val onBindRow: (row: View, provider: Provider, isCurrent: Boolean) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.ProviderHolder>() {

    private val items = ArrayList<Provider>()
    private var currentId: String? = null

    fun submit(providers: List<Provider>, current: String?) {
        items.clear()
        items.addAll(providers)
        currentId = current
        notifyDataSetChanged()
    }

    fun providerAt(position: Int): Provider? = items.getOrNull(position)

    fun selectedPosition(id: String?): Int = items.indexOfFirst { it.id == id }

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    /** Order after a drag, so the repository can persist one write instead of a swap per frame. */
    fun orderedIds(): List<String> = items.map { it.id }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProviderHolder {
        val row = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_provider_capsule, parent, false)
        return ProviderHolder(row)
    }

    override fun onBindViewHolder(holder: ProviderHolder, position: Int) {
        val provider = items[position]
        val isCurrent = provider.id == currentId
        holder.name.text = provider.name
        holder.itemView.tag = holder
        // As in the reference board the whole pill only switches the provider, silently. Editing
        // lives behind the more button (or a long press), so a stray tap never navigates away.
        holder.itemView.setOnClickListener { onClick(provider) }
        holder.itemView.setOnLongClickListener {
            onMore(provider, holder.itemView)
            true
        }
        holder.more.setOnClickListener { onMore(provider, holder.more) }
        holder.more.setOnLongClickListener {
            onDragHandle(holder)
            true
        }
        onBindRow(holder.itemView, provider, isCurrent)
    }

    class ProviderHolder(row: View) : RecyclerView.ViewHolder(row) {
        val name: TextView = row.findViewById(R.id.tvProviderName)
        val url: TextView = row.findViewById(R.id.tvProviderUrl)
        val current: TextView = row.findViewById(R.id.tvProviderCurrent)
        val more: android.widget.ImageButton = row.findViewById(R.id.btnProviderMore)
    }
}
