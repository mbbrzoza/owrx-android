package pl.sp8mb.owrx.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import pl.sp8mb.owrx.map.MapRepository
import pl.sp8mb.owrx.ui.vm.MapViewModel

private enum class MapLayer { RECEIVERS, DATABASE, DECODED }
private val RECEIVER_MODES = setOf("KiwiSDR", "OpenWebRX", "WebSDR")
private val DATABASE_MODES = setOf("Stations", "Repeaters")
private val AIRCRAFT_MODES = setOf("ADSB", "VDL2", "HFDL", "Mode-S", "Aircraft")
private fun layerOf(p: MapRepository.Position): MapLayer = when {
    p.local -> MapLayer.DECODED
    p.mode in RECEIVER_MODES -> MapLayer.RECEIVERS
    p.mode in DATABASE_MODES -> MapLayer.DATABASE
    else -> MapLayer.DECODED   // APRS/FT8/AIS/... = zdekodowane stacje
}

@Composable
fun MapScreen(vm: MapViewModel = hiltViewModel()) {
    val positions by vm.positions.collectAsState()
    val receiver by vm.receiver.collectAsState()
    val connected by vm.connected.collectAsState()
    var holder by remember { mutableStateOf<ClusterMapHolder?>(null) }

    // Warstwy „stałe" (sieć odbiorników, bazy) domyślnie UKRYTE — żeby nie zaciemniać mapy;
    // zdekodowane stacje (APRS/FT8/local) zawsze widoczne. Celowo `remember` (nie Saveable):
    // przy każdym wejściu na mapę startują wyłączone, włączenie nie „przykleja się".
    var showReceivers by remember { mutableStateOf(false) }
    var showDatabase by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        vm.connect()
        onDispose { vm.disconnect() }
    }

    val counts = remember(positions) { positions.values.groupingBy { layerOf(it) }.eachCount() }
    val filtered = remember(positions, showReceivers, showDatabase) {
        positions.values.filter {
            when (layerOf(it)) {
                MapLayer.RECEIVERS -> showReceivers
                MapLayer.DATABASE -> showDatabase
                MapLayer.DECODED -> true
            }
        }
    }

    // Re-render markers when data/filters change (the map move/zoom path is handled by the holder).
    LaunchedEffect(filtered, receiver, holder) {
        holder?.setData(filtered, receiver)
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
                    isTilesScaledToDpi = true
                    controller.setZoom(6.0)
                    controller.setCenter(GeoPoint(52.0, 19.0)) // Poland fallback until receiver arrives
                    holder = ClusterMapHolder(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // przełączniki warstw (lewy-górny)
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = showReceivers,
                onClick = { showReceivers = !showReceivers },
                label = { Text("Odbiorniki ${counts[MapLayer.RECEIVERS] ?: 0}", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = showDatabase,
                onClick = { showDatabase = !showDatabase },
                label = { Text("Bazy ${counts[MapLayer.DATABASE] ?: 0}", style = MaterialTheme.typography.labelSmall) },
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Text(
                "${filtered.size} na mapie · zdekod. ${counts[MapLayer.DECODED] ?: 0}" +
                    if (connected) "" else " (łączenie…)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Holds the MapView and renders position reports as small, age-coloured markers with
 * pixel-grid clustering (dense areas collapse into a counted bubble; tapping a cluster
 * zooms in). Re-clusters on map move/zoom (debounced) and on data change. Markers are
 * tappable for info (callsign, mode, age, comment). Receiver shown as a distinct marker.
 */
private class ClusterMapHolder(val mv: MapView) {
    private var positions: Collection<MapRepository.Position> = emptyList()
    private var receiver: MapRepository.ReceiverInfo? = null
    private var centeredOnReceiver = false

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    // icon caches — avoid regenerating identical bitmaps each redraw
    private val dotCache = HashMap<Long, BitmapDrawable>()
    private val clusterCache = HashMap<Int, BitmapDrawable>()
    private var receiverIcon: BitmapDrawable? = null
    private var localIcon: BitmapDrawable? = null
    private var aprsSheets: Array<Bitmap?>? = null
    private val aprsCache = HashMap<Long, BitmapDrawable>()
    private val aircraftCache = HashMap<Int, BitmapDrawable>()

    init {
        mv.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { scheduleRender(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { scheduleRender(); return false }
        })
    }

    fun setData(p: Collection<MapRepository.Position>, r: MapRepository.ReceiverInfo?) {
        positions = p
        receiver = r
        if (!centeredOnReceiver && r != null) {
            mv.controller.setZoom(8.0)
            mv.controller.setCenter(GeoPoint(r.lat, r.lon))
            centeredOnReceiver = true
        }
        render()
    }

    private fun scheduleRender() {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { render() }
        pending = r
        handler.postDelayed(r, 120)
    }

    private fun render() {
        val ctx = mv.context
        val proj = mv.projection
        val now = System.currentTimeMillis()
        val r = 56.0 * ctx.resources.displayMetrics.density
        val w = mv.width
        val h = mv.height
        val margin = 140

        // Zdekodowane stacje (APRS/FT8/AIS + lokalne) pokazuj ZAWSZE pojedynczo (każda swój symbol);
        // klastruj tylko warstwy stałe (sieć odbiorników / bazy), bo są gęste.
        val individuals = ArrayList<MapRepository.Position>()
        val buckets = HashMap<Long, MutableList<MapRepository.Position>>()
        for (p in positions) {
            if (layerOf(p) == MapLayer.DECODED) { individuals.add(p); continue }
            val pt = proj.toPixels(GeoPoint(p.lat, p.lon), null)
            if (w > 0 && (pt.x < -margin || pt.x > w + margin || pt.y < -margin || pt.y > h + margin)) continue
            val gx = Math.floor(pt.x / r).toLong()
            val gy = Math.floor(pt.y / r).toLong()
            val key = (gx shl 32) xor (gy and 0xffffffffL)
            buckets.getOrPut(key) { ArrayList() }.add(p)
        }

        mv.overlays.clear()
        for ((_, list) in buckets) {
            if (list.size == 1) addSingle(list[0], now) else addCluster(list)
        }
        for (p in individuals) when {
            p.gridLat != null && p.gridLon != null -> addGrid(p, now)  // lokator → kwadrat siatki
            p.local -> addLocal(p, now)
            else -> addSingle(p, now)
        }
        receiver?.let { addReceiver(it) }
        mv.invalidate()
    }

    private fun addSingle(p: MapRepository.Position, now: Long) {
        val m = Marker(mv)
        m.position = GeoPoint(p.lat, p.lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = iconFor(p, now)
        m.title = p.callsign + (p.mode?.let { "  [$it]" } ?: "")
        m.snippet = buildString {
            append(agoStr(now - p.lastSeen))
            p.comment?.takeIf { it.isNotBlank() }?.let { append("\n"); append(it.take(180)) }
        }
        mv.overlays.add(m)
    }

    private fun addCluster(list: List<MapRepository.Position>) {
        var la = 0.0; var lo = 0.0
        for (p in list) { la += p.lat; lo += p.lon }
        val m = Marker(mv)
        m.position = GeoPoint(la / list.size, lo / list.size)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = clusterIcon(list.size)
        m.setInfoWindow(null)
        m.setOnMarkerClickListener { marker, _ ->
            mv.controller.animateTo(
                marker.position,
                (mv.zoomLevelDouble + 2.0).coerceAtMost(mv.maxZoomLevel),
                400L,
            )
            true
        }
        mv.overlays.add(m)
    }

    private fun addLocal(p: MapRepository.Position, now: Long) {
        val m = Marker(mv)
        m.position = GeoPoint(p.lat, p.lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = iconFor(p, now)   // symbol APRS jeśli jest, inaczej wyróżnik magenta
        m.title = p.callsign + (p.mode?.let { "  [$it]" } ?: "")
        m.snippet = buildString {
            append("zdekodowane lokalnie · " + agoStr(now - p.lastSeen))
            p.comment?.takeIf { it.isNotBlank() }?.let { append("\n"); append(it.take(180)) }
        }
        mv.overlays.add(m)
    }

    // Kwadrat siatki Maidenhead (FT8/WSPR/...) jako półprzezroczysty prostokąt z info.
    private fun addGrid(p: MapRepository.Position, now: Long) {
        val gLat = p.gridLat ?: return
        val gLon = p.gridLon ?: return
        val d = mv.context.resources.displayMetrics.density
        val poly = Polygon(mv)
        poly.points = listOf(
            GeoPoint(p.lat - gLat / 2, p.lon - gLon / 2),
            GeoPoint(p.lat - gLat / 2, p.lon + gLon / 2),
            GeoPoint(p.lat + gLat / 2, p.lon + gLon / 2),
            GeoPoint(p.lat + gLat / 2, p.lon - gLon / 2),
        )
        poly.fillPaint.color = 0x33AB47BC.toInt()       // półprzezroczysty fiolet
        poly.outlinePaint.color = 0xCCAB47BC.toInt()
        poly.outlinePaint.strokeWidth = 2f * d
        poly.title = p.callsign + (p.mode?.let { "  [$it]" } ?: "")
        poly.snippet = buildString {
            append(agoStr(now - p.lastSeen))
            p.comment?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
        }
        poly.setOnClickListener { polygon, _, _ -> polygon.showInfoWindow(); true }
        mv.overlays.add(poly)
    }

    private fun addReceiver(r: MapRepository.ReceiverInfo) {
        val icon = receiverIcon ?: BitmapDrawable(mv.context.resources, receiverBitmap(mv.context)).also { receiverIcon = it }
        val m = Marker(mv)
        m.position = GeoPoint(r.lat, r.lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = icon
        m.title = "📡 " + (r.name ?: "Odbiornik")
        m.snippet = r.location ?: ""
        mv.overlays.add(m)
    }

    // Wybór ikony markera: symbol APRS (sprite) → samolot (tryby lotnicze) → wyróżnik local → kropka.
    private fun iconFor(p: MapRepository.Position, now: Long): BitmapDrawable = when {
        p.symIndex != null -> aprsIcon(p.symTable ?: "/", p.symIndex!!, p.symTableIndex ?: 0)
        p.mode in AIRCRAFT_MODES -> aircraftIcon(p.course)
        p.local -> localIcon ?: BitmapDrawable(mv.context.resources, localBitmap(mv.context)).also { localIcon = it }
        else -> dotIcon(ageColor(now - p.lastSeen), p.isLocator)
    }

    private fun sheets(): Array<Bitmap?> {
        aprsSheets?.let { return it }
        val arr = arrayOfNulls<Bitmap>(3)
        for (i in 0..2) {
            try {
                mv.context.assets.open("aprs-symbols/aprs-symbols-24-$i.png").use {
                    arr[i] = android.graphics.BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) { /* brak sprite → fallback w aprsIcon */ }
        }
        aprsSheets = arr
        return arr
    }

    // Wytnij symbol APRS z arkusza (16 kol × 24px) wg index; nałóż overlay (arkusz 2) wg tableindex
    // gdy tablica to znak overlay; przeskaluj do rozmiaru dp. Jak web (AprsMarker.js).
    private fun aprsIcon(table: String, index: Int, tableIndex: Int): BitmapDrawable {
        val key = (table.hashCode().toLong() shl 40) xor (index.toLong() shl 16) xor tableIndex.toLong()
        aprsCache[key]?.let { return it }
        val sh = sheets()
        val base = if (table == "/") sh[0] else sh[1]
        val d = mv.context.resources.displayMetrics.density
        val out: Bitmap = if (base == null || index < 0 || (index / 16) * 24 + 24 > base.height) {
            dotBitmap(mv.context, 0xFF1E88E5.toInt(), false)
        } else {
            val sx = (index % 16) * 24
            val sy = (index / 16) * 24
            var crop = Bitmap.createBitmap(base, sx, sy, 24, 24)
            if (table != "/" && table != "\\") {
                val ov = sh[2]
                if (ov != null && tableIndex >= 0 && (tableIndex / 16) * 24 + 24 <= ov.height) {
                    val mutable = crop.copy(Bitmap.Config.ARGB_8888, true)
                    val oc = Bitmap.createBitmap(ov, (tableIndex % 16) * 24, (tableIndex / 16) * 24, 24, 24)
                    Canvas(mutable).drawBitmap(oc, 0f, 0f, null)
                    crop = mutable
                }
            }
            val target = (24f * d).toInt().coerceAtLeast(20)
            Bitmap.createScaledBitmap(crop, target, target, true)
        }
        val dr = BitmapDrawable(mv.context.resources, out)
        aprsCache[key] = dr
        return dr
    }

    // Ikona samolotu obrócona wg kursu (tryby ADSB/VDL2/HFDL).
    private fun aircraftIcon(course: Float?): BitmapDrawable {
        val bucket = (((course ?: 0f).toInt() % 360 + 360) % 360) / 15 * 15
        aircraftCache[bucket]?.let { return it }
        val dr = BitmapDrawable(mv.context.resources, aircraftBitmap(mv.context, bucket.toFloat()))
        aircraftCache[bucket] = dr
        return dr
    }

    private fun dotIcon(color: Int, locator: Boolean): BitmapDrawable {
        val key = (color.toLong() shl 1) or (if (locator) 1L else 0L)
        return dotCache.getOrPut(key) {
            BitmapDrawable(mv.context.resources, dotBitmap(mv.context, color, locator))
        }
    }

    private fun clusterIcon(count: Int): BitmapDrawable {
        // bucket counts so we don't make a bitmap per exact number
        val bucket = when {
            count < 10 -> count
            count < 100 -> count / 10 * 10
            else -> count / 100 * 100
        }
        return clusterCache.getOrPut(bucket) {
            BitmapDrawable(mv.context.resources, clusterBitmap(mv.context, count))
        }
    }
}

// ── icon bitmaps (drawn programmatically — no drawable resources needed) ──

private fun ageColor(ageMs: Long): Int = when {
    ageMs < 5 * 60_000 -> 0xFF43A047.toInt()   // świeże — zielony
    ageMs < 20 * 60_000 -> 0xFFFBC02D.toInt()  // żółty
    ageMs < 40 * 60_000 -> 0xFFFB8C00.toInt()  // pomarańczowy
    else -> 0xFF9E9E9E.toInt()                  // stare — szary
}

private fun agoStr(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s temu"
        s < 3600 -> "${s / 60} min temu"
        else -> "${s / 3600} h temu"
    }
}

private fun dotBitmap(ctx: Context, color: Int, locator: Boolean): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val s = (14f * d).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = color
    if (locator) {
        c.drawRect(s * 0.16f, s * 0.16f, s * 0.84f, s * 0.84f, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF101010.toInt(); p.strokeWidth = d
        c.drawRect(s * 0.16f, s * 0.16f, s * 0.84f, s * 0.84f, p)
    } else {
        val r = s / 2f
        c.drawCircle(r, r, r - d, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF101010.toInt(); p.strokeWidth = d
        c.drawCircle(r, r, r - d, p)
    }
    return bmp
}

private fun clusterBitmap(ctx: Context, count: Int): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val s = ((if (count >= 100) 46f else if (count >= 10) 40f else 34f) * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = s / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xDD1565C0.toInt()
    c.drawCircle(r, r, r - d, p)
    p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 2f * d
    c.drawCircle(r, r, r - 2f * d, p)
    p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
    p.textAlign = Paint.Align.CENTER
    val label = if (count >= 1000) "${count / 1000}k+" else count.toString()
    p.textSize = (if (label.length >= 4) 11f else if (label.length == 3) 13f else 15f) * d
    val fm = p.fontMetrics
    c.drawText(label, r, r - (fm.ascent + fm.descent) / 2f, p)
    return bmp
}

private fun localBitmap(ctx: Context): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val s = (18f * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = s / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFE040FB.toInt()   // magenta — wyraźnie inny od reszty
    c.drawCircle(r, r, r - d, p)
    p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 2f * d
    c.drawCircle(r, r, r - 2f * d, p)
    return bmp
}

private fun aircraftBitmap(ctx: Context, course: Float): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val s = (24f * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.rotate(course, s / 2f, s / 2f)
    val cx = s / 2f
    val path = Path()
    path.moveTo(cx, s * 0.10f)               // dziób
    path.lineTo(cx + s * 0.09f, s * 0.50f)
    path.lineTo(cx + s * 0.42f, s * 0.66f)   // prawe skrzydło
    path.lineTo(cx + s * 0.42f, s * 0.74f)
    path.lineTo(cx + s * 0.09f, s * 0.64f)
    path.lineTo(cx + s * 0.09f, s * 0.84f)
    path.lineTo(cx + s * 0.20f, s * 0.92f)   // prawy statecznik
    path.lineTo(cx + s * 0.20f, s * 0.98f)
    path.lineTo(cx, s * 0.90f)
    path.lineTo(cx - s * 0.20f, s * 0.98f)
    path.lineTo(cx - s * 0.20f, s * 0.92f)
    path.lineTo(cx - s * 0.09f, s * 0.84f)
    path.lineTo(cx - s * 0.09f, s * 0.64f)
    path.lineTo(cx - s * 0.42f, s * 0.74f)   // lewe skrzydło
    path.lineTo(cx - s * 0.42f, s * 0.66f)
    path.lineTo(cx - s * 0.09f, s * 0.50f)
    path.close()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF1565C0.toInt(); c.drawPath(path, p)
    p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = d
    c.drawPath(path, p)
    return bmp
}

private fun receiverBitmap(ctx: Context): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val s = (24f * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = s / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFD32F2F.toInt()
    c.drawCircle(r, r, r - d, p)
    p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 2.5f * d
    c.drawCircle(r, r, r - 2.5f * d, p)
    c.drawCircle(r, r, r * 0.32f, p)
    return bmp
}
