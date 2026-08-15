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

        // 搜索：实时过滤（分页）
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                query = q
                items = emptyList()
                offset = 0
                hasMore = true
                adapter.submitList(emptyList())
                loadMoreItems()
            }
        })

        // 滚动到顶部加载更早的历史（借鉴 hermes 的分页加载）
        historyList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                val lm = v.layoutManager as LinearLayoutManager
                if (lm.findFirstVisibleItemPosition() <= 2 && hasMore) {
                    loadMoreItems()
                }
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
                .setTitle(getString(R.string.dlg_clear_title))
                .setMessage(getString(R.string.dlg_clear_message))
                .setPositiveButton(getString(R.string.action_clear)) { _, _ ->
                    lifecycleScope.launch {
                        ChatStore.clearAll()
                        adapter.submitList(emptyList())
                        updateEmpty()
                        toast(getString(R.string.toast_cleared))
                    }
                }
                .setNegativeButton(getString(R.string.dlg_cancel), null)
                .show()
        }

        initialLoad()
    }

    // ---------- 分页加载（借鉴 hermes：最近 PAGE_SIZE 条 + 滚动顶部加载更早） ----------
    private var query = ""
    private var items: List<MessageEntity> = emptyList()
    private var offset = 0
    private var hasMore = true
    @Volatile private var loading = false

    /** 初始加载：最近 PAGE_SIZE 条（倒序，最新在上）。 */
    private fun initialLoad() {
        lifecycleScope.launch {
            val latest = if (query.isEmpty()) ChatStore.loadLatest() else ChatStore.search(query)
            // 倒序显示（最新在上）
            items = latest.reversed()
            offset = latest.size
            adapter.submitList(items)
            updateEmpty()
        }
    }

    /** 加载更早的一页（插到列表尾部）。 */
    private fun loadMoreItems() {
        if (loading || !hasMore) return
        loading = true
        lifecycleScope.launch {
            val older = if (query.isEmpty()) ChatStore.loadMore(offset) else ChatStore.search(query, offset)
            loading = false
            if (older.isEmpty()) { hasMore = false; return@launch }
            // 插到末尾（更早的在下面）
            val merged = items + older.reversed()
            items = merged
            offset += older.size
            adapter.submitList(merged)
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
                toast(getString(R.string.toast_exported, count))
            } catch (e: Exception) {
                toast(getString(R.string.toast_export_failed, e.message))
            }
        }
    }

    private fun doImport(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 先确认是追加还是替换
                val count = BackupManager.import(this@HistoryActivity, uri, ChatStore.getDao(), replace = false)
                toast(getString(R.string.toast_imported, count))
                initialLoad()
            } catch (e: Exception) {
                toast(getString(R.string.toast_import_failed, e.message))
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
        h.prefix.text = if (m.isFromUser) h.itemView.context.getString(R.string.prefix_user) else h.itemView.context.getString(R.string.prefix_assistant)
        // 用 MarkdownRenderer 渲染（与主界面一致）
        com.sherva.voiceassistant.ui.MarkdownRenderer.render(h.content, m.content)
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
