package pl.sp8mb.owrx.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import pl.sp8mb.owrx.ui.vm.MapViewModel

@Composable
fun MapScreen(vm: MapViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val positions by vm.positions.collectAsState()
    val connected by vm.connected.collectAsState()
    var mapView by remember { mutableStateOf<MapView?>(null) }

    DisposableEffect(Unit) {
        vm.connect()
        onDispose { vm.disconnect() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().apply {
                    userAgentValue = ctx.packageName
                    osmdroidBasePath = ctx.cacheDir
                    osmdroidTileCache = java.io.File(ctx.cacheDir, "osmtiles")
                }
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(6.0)
                    controller.setCenter(GeoPoint(52.0, 19.0)) // Poland
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // refresh markers when positions change
        mapView?.let { mv ->
            mv.overlays.clear()
            positions.values.forEach { p ->
                mv.overlays.add(
                    Marker(mv).apply {
                        position = GeoPoint(p.lat, p.lon)
                        title = p.callsign + (p.mode?.let { " ($it)" } ?: "")
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                )
            }
            mv.invalidate()
        }

        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Text(
                "${positions.size} pozycji" + if (connected) "" else " (łączenie…)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
