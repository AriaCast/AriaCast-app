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

        val color = when (log.direction) {
            PacketDirection.IN -> ContextCompat.getColor(holder.itemView.context, R.color.accent_blue)
            PacketDirection.OUT -> ContextCompat.getColor(holder.itemView.context, R.color.secondaryDarkColor)
        }
        holder.direction.setTextColor(color)
    }

    override fun getItemCount(): Int = filteredLogs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val direction: TextView = view.findViewById(R.id.directionText)
        val type: TextView = view.findViewById(R.id.typeText)
        val time: TextView = view.findViewById(R.id.timeText)
        val message: TextView = view.findViewById(R.id.messageText)
    }
}
