package com.scan.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.scan.qr.history.ScanHistoryRepository
import com.scan.qr.scanner.GalleryScanResult
import com.scan.qr.scanner.parseQrContent
import com.scan.qr.ui.GalleryMultiQrScreen
import com.scan.qr.ui.HistoryScreen
import com.scan.qr.ui.ResultScreen
import com.scan.qr.ui.ScannerScreen
import com.scan.qr.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val context = LocalContext.current
    val repository = remember { ScanHistoryRepository(context) }
    var historyItems by remember { mutableStateOf(repository.all()) }
    var result by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var galleryMulti by remember { mutableStateOf<GalleryScanResult?>(null) }

    BackHandler(enabled = showHistory || showSettings || result != null || galleryMulti != null) {
        when {
            galleryMulti != null -> galleryMulti = null
            showSettings -> showSettings = false
            showHistory -> showHistory = false
            else -> result = null
        }
    }

    when {
        showSettings -> SettingsScreen(onBack = { showSettings = false })

        showHistory -> HistoryScreen(
            items = historyItems,
            onBack = { showHistory = false },
            onOpen = { item ->
                showHistory = false
                result = item.content
            },
            onDelete = { item ->
                repository.delete(item.id)
                historyItems = repository.all()
            },
            onClearAll = {
                repository.clear()
                historyItems = repository.all()
            }
        )

        result != null -> ResultScreen(
            content = parseQrContent(result!!),
            onRescan = { result = null },
            onOpenHistory = { showHistory = true },
            onOpenSettings = { showSettings = true }
        )

        galleryMulti != null -> GalleryMultiQrScreen(
            result = galleryMulti!!,
            onSelect = { raw ->
                galleryMulti = null
                val content = parseQrContent(raw)
                repository.add(content)
                historyItems = repository.all()
                result = raw
            },
            onCancel = { galleryMulti = null }
        )

        else -> CameraPermissionGate(
            onGranted = {
                ScannerScreen(
                    onScanned = { raw ->
                        val content = parseQrContent(raw)
                        repository.add(content)
                        historyItems = repository.all()
                        result = raw
                    },
                    onOpenHistory = { showHistory = true },
                    onOpenSettings = { showSettings = true },
                    onMultiQrFromGallery = { galleryMulti = it }
                )
            }
        )
    }
}

@Composable
private fun CameraPermissionGate(onGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (!ok && activity != null) {
            permanentlyDenied = !activity.shouldShowRequestPermissionRationale(
                Manifest.permission.CAMERA
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) {
            askedOnce = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // 从系统设置返回后重新检查权限
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        onGranted()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (permanentlyDenied) "相机权限已被拒绝，请在系统设置中开启"
                else "需要相机权限才能扫码",
                color = Color.White,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = {
                if (permanentlyDenied) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                } else {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            }) {
                Text(if (permanentlyDenied) "去设置" else "重新授权")
            }
        }
    }
}
