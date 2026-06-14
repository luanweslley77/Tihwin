package com.yourcompany.tihwinnew

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GameAdapter(
    private val gameList: MutableList<GameEntry>,
    private val onRemoveClickListener: (GameEntry) -> Unit
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val gameTitle: TextView = view.findViewById(R.id.textViewGameTitle)
        val removeButton: Button = view.findViewById(R.id.buttonRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_game, parent, false)
        return GameViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = gameList[position]
        holder.gameTitle.text = "${game.name} (${game.id})"
        holder.removeButton.setOnClickListener {
            onRemoveClickListener(game)
        }
    }

    override fun getItemCount() = gameList.size

    fun updateData(newGames: List<GameEntry>) {
                val diffCallback = GameEntryDiffCallback(ArrayList(this.gameList), newGames)
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        gameList.clear()
        gameList.addAll(newGames)
        diffResult.dispatchUpdatesTo(this)
    }
}