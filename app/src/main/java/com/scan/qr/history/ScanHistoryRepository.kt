package com.scan.qr.history

import android.content.Context
import com.scan.qr.scanner.QrContent
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val id: Long,
    val time: Long,
    val typeLabel: String,
    val content: String
)

/** 极简扫码历史：SharedPreferences + JSON，最多保留 200 条 */
class ScanHistoryRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("scan_history", Context.MODE_PRIVATE)

    fun all(): List<HistoryItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        HistoryItem(
                            id = obj.optLong("id"),
                            time = obj.optLong("time"),
                            typeLabel = obj.optString("type"),
                            content = obj.optString("content")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(content: QrContent) {
        val item = HistoryItem(
            id = System.currentTimeMillis(),
            time = System.currentTimeMillis(),
            typeLabel = content.type.label,
            content = content.raw
        )
        val list = (listOf(item) + all()).take(MAX)
        save(list)
    }

    fun delete(id: Long) {
        save(all().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(list: List<HistoryItem>) {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("time", item.time)
                    .put("type", item.typeLabel)
                    .put("content", item.content)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 200
    }
}
