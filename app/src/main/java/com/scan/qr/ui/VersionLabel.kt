package com.scan.qr.ui

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 版本标签：格式为 "v1.8 · 装于 09-15 18:47"；无安装时间时退化为 "v1.8" */
fun stampLabel(version: String, installedAt: Long): String =
    if (installedAt > 0L) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(installedAt))
        "v$version · 装于 $time"
    } else {
        "v$version"
    }

/**
 * 读取"当前已安装的包"的真实版本号与安装时间。
 * 之所以不用 BuildConfig：BuildConfig 反映的是"编译时"的版本，
 * 而这里要看的是"手机上装的这一份"，安装时间能一眼暴露旧包没被替换的问题。
 */
fun installedStampLabel(context: Context): String {
    val info = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: Exception) {
        null
    }
    val version = info?.versionName?.ifBlank { null } ?: com.scan.qr.BuildConfig.VERSION_NAME
    val installedAt = info?.lastUpdateTime ?: 0L
    return stampLabel(version, installedAt)
}
