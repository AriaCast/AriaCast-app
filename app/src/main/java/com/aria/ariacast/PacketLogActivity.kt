package com.aria.ariacast

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

class PacketLogAdapter(private val logs: MutableList<PacketLog>) : RecyclerView.Adapter<PacketLogAdapter.ViewHolder>() {

    fun addLog(log: PacketLog) {
        logs.add(0, log)
        if (logs.size > 500) logs.removeAt(logs.size - 1)
        notifyItemInserted(0)
    }

    fun clear() {
        val size = logs.size
        logs.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_packet_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        holder.direction.text = log.direction.name
        holder.type.text = log.type.name
        holder.time.text = log.timestamp
        holder.message.text = log.message

        val color = when (log.direction) {
            PacketDirection.IN -> ContextCompat.getColor(holder.itemView.context, R.color.accent_blue)
            PacketDirection.OUT -> ContextCompat.getColor(holder.itemView.context, R.color.green_700)
        }
        holder.direction.setTextColor(color)
    }

    override fun getItemCount(): Int = logs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val direction: TextView = view.findViewById(R.id.directionText)
        val type: TextView = view.findViewById(R.id.typeText)
        val time: TextView = view.findViewById(R.id.timeText)
        val message: TextView = view.findViewById(R.id.messageText)
    }
}
