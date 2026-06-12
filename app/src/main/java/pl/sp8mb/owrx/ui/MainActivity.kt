package pl.sp8mb.owrx.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import pl.sp8mb.owrx.ui.screens.DebugScreen
import pl.sp8mb.owrx.ui.theme.OwrxTheme
import androidx.compose.foundation.layout.Box

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            OwrxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        DebugScreen()
                    }
                }
            }
        }
    }
}
