package com.keyfekederradyo.android

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Neon station grid: glass card, big cover, name + genre; the playing card glows. */
class StationAdapter(
    private val onClick: (Station) -> Unit,
    private val isFavorite: (Station) -> Boolean,
    private val onFavorite: (Station) -> Unit,
) : RecyclerView.Adapter<StationAdapter.Holder>() {
    private var items = emptyList<Station>()
    private var playingUrl: String? = null
    private var animateFrom = 0

    private val playingListener: (String?) -> Unit = { url ->
        val old = playingUrl
        playingUrl = url
        items.indexOfFirst { it.resolvedUrl == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        items.indexOfFirst { it.resolvedUrl == url }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { PlaybackState.addListener(playingListener) }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { PlaybackState.removeListener(playingListener) }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<Station>) {
        items = list
        animateFrom = 0
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() { animateFrom = Int.MAX_VALUE; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ui = Ui(parent.context)
        val card = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(214)).apply {
                setMargins(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7))
            }
            isClickable = true; isFocusable = true
        }
        val column = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(12))
        }
        val logo = StationArtworkView(parent.context)
        column.addView(logo, LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)).apply { bottomMargin = ui.dp(12) })
        val title = ui.text("", 14.5f, Neon.TEXT, true).apply { gravity = Gravity.CENTER }
        val meta = ui.text("", 11.5f, Neon.MUTED).apply { gravity = Gravity.CENTER }
        val live = ui.label("● Canlı", Neon.ORANGE).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        column.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        column.addView(live, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        card.addView(column, FrameLayout.LayoutParams(-1, -1))
        val fav = FavoriteButton(parent.context, 21f)
        card.addView(fav, FrameLayout.LayoutParams(ui.dp(46), ui.dp(46), Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(2); rightMargin = ui.dp(2) })
        return Holder(card, logo, title, meta, live, fav)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val station = items[position]
        val ui = Ui(holder.itemView.context)
        val active = station.resolvedUrl == playingUrl
        holder.itemView.background = if (active)
            ui.glass(26f, Neon.withAlpha(Neon.ORANGE, 150), gradient = intArrayOf(0x47FF7A1A, 0x26FF2E88, 0x14FFFFFF))
        else ui.glass(26f)
        holder.logo.bind(station.name, station.genre, station.logoUrl)
        holder.logo.setPlaying(active)
        holder.logo.contentDescription = station.name
        holder.title.text = station.name
        holder.meta.text = listOf(station.genre, station.country).filter { it.isNotBlank() }.distinct().joinToString(" • ").ifBlank { "Canlı radyo" }
        holder.live.visibility = if (active) View.VISIBLE else View.GONE

        holder.fav.setOn(isFavorite(station))
        holder.fav.setOnClickListener { holder.fav.animateTo(!holder.fav.isOn); onFavorite(station) }
        holder.itemView.setOnClickListener { Ui.pop(it); onClick(station) }

        if (position >= animateFrom) {
            holder.itemView.alpha = 0f; holder.itemView.translationY = ui.dpf(18f)
            holder.itemView.animate().alpha(1f).translationY(0f)
                .setStartDelay((position % 8) * 35L).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
            animateFrom = position + 1
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f; holder.itemView.translationY = 0f
        holder.logo.setPlaying(false)
        super.onViewRecycled(holder)
    }

    class Holder(
        view: View, val logo: StationArtworkView, val title: TextView, val meta: TextView,
        val live: TextView, val fav: FavoriteButton,
    ) : RecyclerView.ViewHolder(view)
}
