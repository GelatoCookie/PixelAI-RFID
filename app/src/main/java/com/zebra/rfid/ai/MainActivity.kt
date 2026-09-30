package com.zebra.rfid.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zebra.rfid.ai.ui.theme.AIRFIDTheme

class MainActivity : ComponentActivity() {

    private val rfidViewModel: RfidViewModel by viewModels()
    private val aiViewModel: RfidAiViewModel by viewModels()

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                rfidViewModel.onForeground()
            } else {
                Toast.makeText(this, "RFID requires Bluetooth and Location permissions", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (hasRequiredPermissions()) {
                        rfidViewModel.onForeground()
                    } else {
                        requestPermissionLauncher.launch(requiredPermissions)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    rfidViewModel.onBackground()
                }
                else -> {}
            }
        })

        setContent {
            AIRFIDTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScreen(
                        rfidViewModel = rfidViewModel,
                        aiViewModel = aiViewModel
                    )
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
