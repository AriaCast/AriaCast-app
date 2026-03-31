package com.aria.ariacast

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PacketLogActivity : AppCompatActivity() {

    private lateinit var logAdapter: PacketLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_packet_log)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recyclerView = findViewById<RecyclerView>(R.id.logRecyclerView)
        logAdapter = PacketLogAdapter(PacketLogger.logs.toMutableList())
        recyclerView.adapter = logAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        val searchView = findViewById<SearchView>(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                logAdapter.filter(newText ?: "")
                return true
            }
        })

        findViewById<FloatingActionButton>(R.id.clearFab).setOnClickListener {
            PacketLogger.clear()
            logAdapter.clear()
        }

        lifecycleScope.launch {
            PacketLogger.logFlow.collectLatest { log ->
                logAdapter.addLog(log)
            }
        }
    }
}

class PacketLogAdapter(private val allLogs: MutableList<PacketLog>) : RecyclerView.Adapter<PacketLogAdapter.ViewHolder>() {

    private var filteredLogs: MutableList<PacketLog> = allLogs.toMutableList()
    private var currentQuery: String = ""

    fun addLog(log: PacketLog) {
        // Check if we should merge with the last log (which is at index 0 because we add new logs at the beginning)
        val lastLog = allLogs.firstOrNull()
        if (lastLog != null && 
            lastLog.direction == log.direction && 
            lastLog.type == log.type && 
            lastLog.message == log.message) {
            
            // It's the same, it was already updated in PacketLogger, so we just update the UI
            // The object in allLogs is the same one that was updated in PacketLogger
            val filteredIndex = filteredLogs.indexOf(lastLog)
            if (filteredIndex != -1) {
                notifyItemChanged(filteredIndex)
            }
            return
        }

        allLogs.add(0, log)
        if (allLogs.size > 500) allLogs.removeAt(allLogs.size - 1)
        
        if (currentQuery.isEmpty() || matchesQuery(log, currentQuery)) {
            filteredLogs.add(0, log)
            if (filteredLogs.size > 500) filteredLogs.removeAt(filteredLogs.size - 1)
            notifyItemInserted(0)
        }
    }

    fun clear() {
        val size = filteredLogs.size
        allLogs.clear()
        filteredLogs.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun filter(query: String) {
        currentQuery = query
        filteredLogs = if (query.isEmpty()) {
            allLogs.toMutableList()
        } else {
            allLogs.filter { matchesQuery(it, query) }.toMutableList()
        }
        notifyDataSetChanged()
    }

    private fun matchesQuery(log: PacketLog, query: String): Boolean {
        return log.message.contains(query, ignoreCase = true) ||
                log.type.name.contains(query, ignoreCase = true) ||
                log.direction.name.contains(query, ignoreCase = true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_packet_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = filteredLogs[position]
        holder.direction.text = log.direction.name
        holder.type.text = log.type.name
        holder.time.text = log.timestamp
        holder.message.text = log.message

        if (log.count > 1) {
            holder.count.visibility = View.VISIBLE
            holder.count.text = "x${log.count}"
        } else {
            holder.count.visibility = View.GONE
        }

        val color = when (log.direction) {
            PacketDirection.IN -> ContextCompat.getColor(holder.itemView.context, R.color.accent_blue)
            PacketDirection.OUT -> ContextCompat.getColor(holder.itemView.context, R.color.md_theme_primary)
        }
        holder.direction.setTextColor(color)
    }

    override fun getItemCount(): Int = filteredLogs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val direction: TextView = view.findViewById(R.id.directionText)
        val type: TextView = view.findViewById(R.id.typeText)
        val count: TextView = view.findViewById(R.id.countText)
        val time: TextView = view.findViewById(R.id.timeText)
        val message: TextView = view.findViewById(R.id.messageText)
    }
}
