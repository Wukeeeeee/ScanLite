package com.scan.qr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.qr.history.HistoryItem
import com.scan.qr.scanner.categoryLabel
import com.scan.qr.scanner.parseQrContent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 极简扫码历史 */
@Composable
fun HistoryScreen(
    items: List<HistoryItem>,
    onBack: () -> Unit,
    onOpen: (HistoryItem) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("返回", color = Color(0xFFD1D5DB), fontSize = 15.sp)
            }
            Text(
                "扫描历史",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("清空", color = Color(0xFF9CA3AF), fontSize = 14.sp)
                }
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无扫码记录", color = Color(0xFF6B7280), fontSize = 14.sp)
            }
            return
        }

        val groups = items.groupBy { dayLabel(it.time) }
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (day, dayItems) ->
                item(key = "header-$day") {
                    Text(
                        day,
                        color = Color(0xFF9CA3AF),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
                    )
                }
                items(dayItems, key = { it.id }) { item ->
                    HistoryRow(item = item, onOpen = onOpen, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    onOpen: (HistoryItem) -> Unit,
    onDelete: (HistoryItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 分类标签按**内容现算**，不用入库时冻结的 item.typeLabel ——
        // 这样旧记录里写成「网页链接」的微信码也会自动显示成「微信专用内容」。
        val label = remember(item.id) { categoryLabel(parseQrContent(item.content)) }
        Column(Modifier.weight(1f)) {
            Text(
                "${timeLabel(item.time)}  $label",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.content,
                color = Color(0xFFE5E7EB),
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = { onDelete(item) }) {
            Text("删除", color = Color(0xFF6B7280), fontSize = 13.sp)
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

private fun timeLabel(time: Long): String = timeFormat.format(Date(time))

private fun dayLabel(time: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = time }
    return when {
        isSameDay(now, target) -> "今天"
        isSameDay(now.apply { add(Calendar.DAY_OF_YEAR, -1) }, target) -> "昨天"
        else -> dateFormat.format(Date(time))
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
