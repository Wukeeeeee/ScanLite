package com.scan.qr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.qr.scanner.BrowserPref

/**
 * 设置：选一个"默认浏览器"。
 * 选定后扫到网址会直接用它打开（静默、不弹框），不必去系统「默认应用」里改。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val browsers = remember { BrowserPref.listBrowsers(context) }
    var selected by remember { mutableStateOf(BrowserPref.preferred(context)) }

    fun choose(packageName: String?) {
        selected = packageName
        if (packageName == null) BrowserPref.clear(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = onBack) {
                Text("返回", color = Color(0xFFD1D5DB), fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("打开网址时使用", color = Color(0xFF9CA3AF), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "选一个浏览器后，扫到网址会直接用它的打开，不再弹框；" +
                    "选「每次询问」则每次都由系统弹选择让你挑。",
            color = Color(0xFF6B7280),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // 每次询问
            OptionRow(
                label = "每次询问（弹系统选择器）",
                selected = selected == null,
                onSelect = { choose(null) }
            )

            if (browsers.isEmpty()) {
                Text(
                    "未检测到可打开网页的应用",
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            browsers.forEach { b ->
                OptionRow(
                    label = b.label,
                    selected = selected == b.packageName,
                    onSelect = {
                        selected = b.packageName
                        BrowserPref.setPreferred(context, b.packageName, b.label)
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = { onSelect() })
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color(0xFFE5E7EB), fontSize = 16.sp)
    }
}
