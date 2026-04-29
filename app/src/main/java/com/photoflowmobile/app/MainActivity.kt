package com.photoflowmobile.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photoflowmobile.app.navigation.PhotoFlowNavGraph
import com.photoflowmobile.app.ui.screens.PhotoFlowSplash
import com.photoflowmobile.app.ui.theme.PhotoFlowMobileTheme
import com.photoflowmobile.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { mainViewModel.onUsbDeviceDetached(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setOnExitAnimationListener { it.remove() }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register detach receiver (attach is handled via manifest intent-filter + onNewIntent)
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbDetachReceiver, filter)
        }

        // Handle USB device already attached when app cold-starts
        handleUsbIntent(intent)

        setContent {
            val darkMode by mainViewModel.darkMode.collectAsStateWithLifecycle()
            var showSplash by remember { mutableStateOf(true) }

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkMode
                    isAppearanceLightNavigationBars = !darkMode
                }
            }

            PhotoFlowMobileTheme(darkMode = darkMode) {
                if (showSplash) {
                    PhotoFlowSplash { showSplash = false }
                } else {
                    PhotoFlowNavGraph()
                }
            }
        }
    }

    // Called when the OS brings our activity to the foreground because a USB device matching
    // the filter was attached while we were already running (or cold-started by the attachment).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Motorola (and some other hosts) don't dispatch USB_DEVICE_ATTACHED to apps without a
        // matching USB device filter in the manifest. Re-scanning on every resume ensures we
        // catch cameras that were plugged in while the app was backgrounded.
        mainViewModel.rescanUsbDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbDetachReceiver)
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let { mainViewModel.onUsbDeviceAttached(it) }
    }
}
