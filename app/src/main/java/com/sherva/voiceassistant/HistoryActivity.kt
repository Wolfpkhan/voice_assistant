package com.sherva.voiceassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.sherva.voiceassistant.data.ChatStore
import com.sherva.voiceassistant.data.MessageEntity
import com.sherva.voiceassistant.data.BackupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录页：查看全部历史 / 搜索 / 清空 / 导入导出备份。
 * 点击某条消息 → 返回主界面并用该文本继续提问。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var historyList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var exportButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var clearButton: MaterialButton

    private val adapter = HistoryAdapter { msg ->
        // 点击消息：回主界面，用该文本继续提问
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TEXT_PROMPT, msg.content)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        uri?.let { doExport(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { doImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyList = findViewById(R.id.historyList)
        emptyText = findViewById(R.id.emptyText)
        searchInput = findViewById(R.id.searchInput)
        exportButton = findViewById(R.id.exportButton)
        importButton = findViewById(R.id.importButton)
        clearButton = findViewById(R.id.clearButton)
        findViewById<MaterialButton>(R.id.backButton).setOnClickListener { finish() }

        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = adapter

        // 搜索：实时过滤
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                loadMessages(q)
            }
        })

        exportButton.setOnClickListener {
            exportLauncher.launch("chat_backup_${timestamp()}.gz")
        }
        importButton.setOnClickListener {
            importLauncher.launch("application/gzip")
        }
        clearButton.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("确定删除全部聊天记录？此操作不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        ChatStore.clearAll()
                        adapter.submitList(emptyList())
                        updateEmpty()
                        toast("已清空")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        loadMessages("")
    }

    private fun loadMessages(query: String) {
        lifecycleScope.launch {
            val list = if (query.isEmpty()) ChatStore.loadAll() else ChatStore.search(query)
            // 搜索时倒序（最新在上），浏览时正序
            adapter.submitList(if (query.isEmpty()) list else list.reversed())
            updateEmpty()
        }
    }

    private fun updateEmpty() {
        val empty = adapter.itemCount == 0
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        historyList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun doExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val msgs = ChatStore.loadAll()
                val count = BackupManager.export(this@HistoryActivity, uri, msgs)
                toast("已导出 $count 条记录")
            } catch (e: Exception) {
                toast("导出失败: ${e.message}")
            }
        }
    }

    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 先确认是追加还是替换
                val count = BackupManager.import(this@HistoryActivity, uri, ChatStore.getDao(), replace = false)
                toast("已导入 $count 条记录")
                loadMessages("")
            } catch (e: Exception) {
                toast("导入失败: ${e.message}")
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 历史列表适配器。 */
class HistoryAdapter(
    private val onClick: (MessageEntity) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private var items: List<MessageEntity> = emptyList()

    fun submitList(list: List<MessageEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        h.prefix.text = if (m.isFromUser) "你" else "助手"
        h.content.text = m.content
        h.time.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(m.timestamp))
        h.itemView.setOnClickListener { onClick(m) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val prefix: TextView = v.findViewById(R.id.itemPrefix)
        val content: TextView = v.findViewById(R.id.itemContent)
        val time: TextView = v.findViewById(R.id.itemTime)
    }
}
