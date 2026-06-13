package pl.sp8mb.owrx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import pl.sp8mb.owrx.service.OwrxForegroundService
import pl.sp8mb.owrx.session.OwrxSession
import javax.inject.Inject
import pl.sp8mb.owrx.ui.screens.AdminScreen
import pl.sp8mb.owrx.ui.screens.ReceiverScreen
import pl.sp8mb.owrx.ui.screens.ScannerScreen
import pl.sp8mb.owrx.ui.screens.ServerListScreen
import pl.sp8mb.owrx.ui.screens.TetraScreen
import pl.sp8mb.owrx.ui.theme.OwrxTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: OwrxSession

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Pełne zamknięcie: rozłącz sesję (WS+audio), zatrzymaj serwis pierwszoplanowy
     *  (zwalnia wakelock/wifilock + powiadomienie) i usuń zadanie z ostatnich. */
    private fun quitApp() {
        session.disconnect()
        OwrxForegroundService.stop(this)
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestDozeExemption()
        setContent {
            OwrxTheme {
                OwrxApp(onQuit = ::quitApp)
            }
        }
    }

    /** Background audio over LTE dies in Doze on some OEMs — ask once for the exemption. */
    @SuppressLint("BatteryLife")
    private fun requestDozeExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // some ROMs block the dialog; the app still works, just less reliably in Doze
            }
        }
    }
}

private data class NavTab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
private fun OwrxApp(onQuit: () -> Unit) {
    val navController = rememberNavController()
    var showQuit by rememberSaveable { mutableStateOf(false) }
    val tabs = listOf(
        NavTab("servers", "Serwery") { Icon(Icons.Default.Dns, null) },
        NavTab("receiver", "Odbiornik") { Icon(Icons.Default.Radio, null) },
        NavTab("scanner", "Skaner") { Icon(Icons.Default.Radar, null) },
        NavTab("tetra", "TETRA") { Icon(Icons.Default.GraphicEq, null) },
        NavTab("map", "Mapa") { Icon(Icons.Default.Map, null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var navVisible by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // grab handle — always visible, toggles the nav bar
                    Surface(
                        onClick = { navVisible = !navVisible },
                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                        color = Color(0xCC2A3340),
                        modifier = Modifier.size(width = 56.dp, height = 18.dp),
                    ) {
                        Icon(
                            if (navVisible) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = "Pokaż/ukryj nawigację",
                        )
                    }
                    // zamknij aplikację — zawsze dostępne (nawet gdy nawigacja ukryta)
                    Surface(
                        onClick = { showQuit = true },
                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                        color = Color(0xCC5A2330),
                        modifier = Modifier.size(width = 56.dp, height = 18.dp),
                    ) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "Zamknij aplikację",
                            tint = Color(0xFFFFB0B0),
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
                AnimatedVisibility(visible = navVisible) {
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
                ServerListScreen(
                    onConnected = { navController.navigate("receiver") },
                    onAdmin = { id -> navController.navigate("admin/$id") },
                )
            }
            composable("receiver") { ReceiverScreen() }
            composable("scanner") { ScannerScreen() }
            composable("tetra") { TetraScreen() }
            composable("map") { pl.sp8mb.owrx.ui.screens.MapScreen() }
            composable(
                "admin/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) {
                AdminScreen(onExit = { navController.popBackStack() })
            }
        }
    }

    if (showQuit) {
        AlertDialog(
            onDismissRequest = { showQuit = false },
            title = { Text("Zamknij aplikację?") },
            text = { Text("Rozłączy odbiornik, zatrzyma audio i pracę w tle, i zamknie aplikację.") },
            confirmButton = {
                TextButton(onClick = { showQuit = false; onQuit() }) { Text("Zamknij") }
            },
            dismissButton = {
                TextButton(onClick = { showQuit = false }) { Text("Anuluj") }
            },
        )
    }
}
