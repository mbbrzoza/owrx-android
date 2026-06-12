package pl.sp8mb.owrx.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import pl.sp8mb.owrx.ui.screens.ReceiverScreen
import pl.sp8mb.owrx.ui.screens.ScannerScreen
import pl.sp8mb.owrx.ui.screens.ServerListScreen
import pl.sp8mb.owrx.ui.screens.TetraScreen
import pl.sp8mb.owrx.ui.theme.OwrxTheme

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
                OwrxApp()
            }
        }
    }
}

private data class NavTab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
private fun OwrxApp() {
    val navController = rememberNavController()
    val tabs = listOf(
        NavTab("servers", "Serwery") { Icon(Icons.Default.Dns, null) },
        NavTab("receiver", "Odbiornik") { Icon(Icons.Default.Radio, null) },
        NavTab("scanner", "Skaner") { Icon(Icons.Default.Radar, null) },
        NavTab("tetra", "TETRA") { Icon(Icons.Default.GraphicEq, null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("servers") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "servers",
            modifier = Modifier.padding(padding),
        ) {
            composable("servers") {
                ServerListScreen(onConnected = { navController.navigate("receiver") })
            }
            composable("receiver") { ReceiverScreen() }
            composable("scanner") { ScannerScreen() }
            composable("tetra") { TetraScreen() }
        }
    }
}
