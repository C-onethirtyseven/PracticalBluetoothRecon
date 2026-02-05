package com.example.wifibtlogger

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class WhitelistActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)

        listView = findViewById(R.id.whitelistList)
        emptyView = findViewById(R.id.whitelistEmpty)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter
        listView.emptyView = emptyView

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener
            confirmRemove(item)
        }

        loadList()
    }

    private fun loadList() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val items = prefs.getStringSet(PREF_WHITELIST, emptySet())?.toList()?.sorted() ?: emptyList()
        adapter.clear()
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
    }

    private fun confirmRemove(value: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove from Whitelist")
            .setMessage("Are you sure you want to remove $value?")
            .setPositiveButton("Yes") { _, _ ->
                removeFromWhitelist(value)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun removeFromWhitelist(value: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_WHITELIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(value)
        prefs.edit().putStringSet(PREF_WHITELIST, current).apply()
        loadList()
    }

    companion object {
        private const val PREFS_NAME = "wifi_bt_prefs"
        private const val PREF_WHITELIST = "whitelist_keys"
    }
}
