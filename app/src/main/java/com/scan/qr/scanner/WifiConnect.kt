package com.scan.qr.scanner

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 一键连接 Wi-Fi：
 * - Android 10+ (API 29+)：优先使用 WifiNetworkSpecifier 调起系统原生连接对话框（「ScanLite 请求连接网络 SSID」，用户点同意即连）；
 *   如果系统不支持则使用 WifiNetworkSuggestion 或跳转系统 Wi-Fi 设置。
 * - Android 8.0 - 9.0 (API 26-28)：使用 WifiManager.addNetwork + enableNetwork 自动添加并连接。
 */
fun connectToWifi(context: Context, wifi: WifiInfo): OpenResult {
    val ssid = wifi.ssid.trim()
    val password = wifi.password.orEmpty()
    val security = wifi.security

    if (ssid.isEmpty()) return OpenResult.FAILED

    val appContext = context.applicationContext

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setIsHiddenSsid(wifi.hidden)

            val secUpper = security.uppercase()
            when {
                secUpper.contains("WPA3") || secUpper.contains("SAE") -> {
                    if (password.isNotEmpty()) specifierBuilder.setWpa3Passphrase(password)
                }

                secUpper.contains("WPA") || secUpper.contains("WEP") -> {
                    if (password.isNotEmpty()) specifierBuilder.setWpa2Passphrase(password)
                }

                else -> {
                    // 开放网络
                }
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (e: Exception) {
                            // 忽略
                        }
                    }

                    override fun onUnavailable() {
                        // 用户取消或不可用
                    }
                })
                return OpenResult.OK
            }
        } catch (e: Exception) {
            // 回退到 Suggestion 模式
        }

        // 回退 1：WifiNetworkSuggestion
        try {
            val suggestionBuilder = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsHiddenSsid(wifi.hidden)

            val secUpper = security.uppercase()
            when {
                secUpper.contains("WPA3") || secUpper.contains("SAE") -> {
                    if (password.isNotEmpty()) suggestionBuilder.setWpa3Passphrase(password)
                }

                secUpper.contains("WPA") || secUpper.contains("WEP") -> {
                    if (password.isNotEmpty()) suggestionBuilder.setWpa2Passphrase(password)
                }
            }

            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val status = wifiManager.addNetworkSuggestions(listOf(suggestionBuilder.build()))
                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Toast.makeText(appContext, "已添加 Wi-Fi 建议，靠近时将自动连接", Toast.LENGTH_LONG).show()
                    return OpenResult.OK
                }
            }
        } catch (e: Exception) {
            // 忽略
        }

        // 回退 2：打开系统 Wi-Fi 设置页
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            OpenResult.OK
        } catch (e: Exception) {
            OpenResult.FAILED
        }
    }

    // ---- API 26 - 28 (Android 8.0 - 9.0) ----
    return try {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return OpenResult.FAILED

        @Suppress("DEPRECATION")
        if (!wifiManager.isWifiEnabled) {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true
        }

        @Suppress("DEPRECATION")
        val conf = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$ssid\""
            hiddenSSID = wifi.hidden
            val secUpper = security.uppercase()
            when {
                secUpper.contains("WPA") -> {
                    preSharedKey = "\"$password\""
                }

                secUpper.contains("WEP") -> {
                    wepKeys[0] = "\"$password\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(android.net.wifi.WifiConfiguration.GroupCipher.WEP40)
                }

                else -> {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                }
            }
        }

        @Suppress("DEPRECATION")
        val netId = wifiManager.addNetwork(conf)
        if (netId != -1) {
            @Suppress("DEPRECATION")
            wifiManager.disconnect()
            @Suppress("DEPRECATION")
            wifiManager.enableNetwork(netId, true)
            @Suppress("DEPRECATION")
            wifiManager.reconnect()
            OpenResult.OK
        } else {
            OpenResult.FAILED
        }
    } catch (e: Exception) {
        OpenResult.FAILED
    }
}
