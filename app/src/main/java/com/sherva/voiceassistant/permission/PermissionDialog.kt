package com.sherva.voiceassistant.permission

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sherva.voiceassistant.R

/**
 * ★ 权限引导对话框：逐项展示「为什么需要」+ 实时状态 + 一键跳转。
 *
 * 用法：
 *   PermissionDialog.show(activity)                    // 全部（必需+增强）
 *   PermissionDialog.show(activity, onlyMissing=true)  // 只列未授权项
 *
 * 每次从系统设置返回（onResume）由调用方决定是否重新 show 刷新状态。
 */
object PermissionDialog {

    /** 显示权限引导；返回 dialog 实例便于宿主在 onResume 时 dismiss+重建刷新。 */
    fun show(activity: Activity, onlyMissing: Boolean = false, onAllGranted: (() -> Unit)? = null): AlertDialog {
        val items = if (onlyMissing)
            PermissionCenter.missingAll(activity) else PermissionCenter.list(activity)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_permissions, null)
        val listContainer = view.findViewById<LinearLayout>(R.id.permList)
        view.findViewById<TextView>(R.id.permIntro).setText(
            if (onlyMissing) R.string.perm_intro_missing else R.string.perm_intro_all
        )

        // 分组渲染：必需在前，增强在后
        val sorted = items.sortedBy { it.level.ordinal }
        var lastLevel: PermissionCenter.Level? = null
        for (item in sorted) {
            if (item.level != lastLevel) {
                lastLevel = item.level
                val header = TextView(activity).apply {
                    text = activity.getString(
                        if (item.level == PermissionCenter.Level.REQUIRED) R.string.perm_group_required
                        else R.string.perm_group_enhanced
                    )
                    textSize = 12f
                    setPadding(0, 14, 0, 2)
                }
                listContainer.addView(header)
            }
            listContainer.addView(renderItem(activity, item))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.perm_dialog_title))
            .setView(view)
            .setPositiveButton(activity.getString(R.string.perm_done)) { d, _ ->
                d.dismiss()
                if (PermissionCenter.missingAll(activity).isEmpty()) onAllGranted?.invoke()
            }
            .setCancelable(true)
            .show()
        return dialog
    }

    private fun renderItem(activity: Activity, item: PermissionCenter.Item): View {
        val v = LayoutInflater.from(activity).inflate(R.layout.item_permission, null)
        v.findViewById<TextView>(R.id.permIcon).text = item.icon
        v.findViewById<TextView>(R.id.permTitle).setText(item.titleRes)
        v.findViewById<TextView>(R.id.permDesc).setText(item.descRes)

        val btn = v.findViewById<Button>(R.id.permAction)
        val granted = item.grantedNow(activity)
        if (granted) {
            btn.text = activity.getString(R.string.perm_state_ok)
            btn.isEnabled = false
        } else {
            btn.text = activity.getString(
                if (item.level == PermissionCenter.Level.REQUIRED) R.string.perm_action_required
                else R.string.perm_action_optional
            )
            btn.setOnClickListener { item.request(activity) }
        }
        return v
    }
}
