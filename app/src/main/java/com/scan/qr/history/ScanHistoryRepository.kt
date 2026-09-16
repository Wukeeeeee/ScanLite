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
        val now = System.currentTimeMillis()
        val newItem = JSONObject()
            .put("id", now)
            .put("time", now)
            .put("type", content.type.label)
            .put("content", content.raw)

        // 直接在 JSONArray 上操作：头部插入新条目，截断到 MAX，一次序列化写入。
        // 避免"反序列化为 Kotlin 对象列表 → 修改 → 序列化回 JSON"的双重转换。
        val raw = prefs.getString(KEY, null)
        val existing = if (raw != null) {
            try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        } else {
            JSONArray()
        }
        val result = JSONArray()
        result.put(newItem)
        val limit = (MAX - 1).coerceAtMost(existing.length())
        for (i in 0 until limit) {
            result.put(existing.optJSONObject(i) ?: continue)
        }
        prefs.edit().putString(KEY, result.toString()).apply()
    }

    fun delete(id: Long) {
        val raw = prefs.getString(KEY, null) ?: return
        val existing = try { JSONArray(raw) } catch (e: Exception) { return }
        val result = JSONArray()
        for (i in 0 until existing.length()) {
            val obj = existing.optJSONObject(i) ?: continue
            if (obj.optLong("id") != id) result.put(obj)
        }
        prefs.edit().putString(KEY, result.toString()).apply()
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
