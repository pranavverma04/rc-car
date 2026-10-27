package com.pranav.rcpilot

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import kotlin.math.cos
import kotlin.math.sin

// Matte transmitter body, cream lettering, one hobby-orange accent
private val Body = Color(0xFF1B1A18)
private val Plate = Color(0xFF252320)
private val Groove = Color(0xFF34312C)
private val Edge = Color(0xFF0F0E0D)
private val Ink = Color(0xFFEDE6D6)
private val Dim = Color(0xFF8E877B)
private val Signal = Color(0xFFFF6A1F)
private val LedOn = Color(0xFF8BE05A)
private val LedOff = Color(0xFF5A2A22)
private val Tape = Color(0xFF111111)

enum class Mode(val label: String) { STICKS("2 sticks"), ONE("1 stick"), TILT("Tilt") }

class MainActivity : ComponentActivity(), SensorEventListener {
    private val link by lazy { CarLink(lifecycleScope) }
    private lateinit var cm: ConnectivityManager
    private lateinit var sensors: SensorManager

    /** Raw gravity in landscape screen terms: x = steering axis, y = forward/back tilt. */
    private val tiltRaw = mutableStateOf(Offset.Zero)

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { link.network = network }
        override fun onLost(network: Network) { if (link.network == network) link.network = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        cm = getSystemService(ConnectivityManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        val prefs = getSharedPreferences("rc", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Signal, background = Body, surface = Plate, onSurface = Ink)) {
                Cockpit(
                    link = link,
                    prefs = prefs,
                    tiltRaw = { tiltRaw.value },
                    openWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // the car's Wi-Fi has no internet
            .build()
        cm.registerNetworkCallback(req, netCallback)
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        g?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        link.start()
    }

    override fun onStop() {
        link.stop()   // app in background: wheels stop
        sensors.unregisterListener(this)
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        super.onStop()
    }

    override fun onSensorChanged(e: SensorEvent) {
        // Locked to landscape with the phone's top to the left: screen-right is device -y, screen-out is device z
        val prev = tiltRaw.value
        val a = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.2f else 1f   // smooth raw accelerometer
        tiltRaw.value = Offset(prev.x + a * (e.values[1] - prev.x), prev.y + a * (e.values[2] - prev.y))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
private fun Cockpit(link: CarLink, prefs: android.content.SharedPreferences, tiltRaw: () -> Offset, openWifi: () -> Unit) {
    val status by link.status.collectAsState()
    val haptic = LocalHapticFeedback.current

    var mode by remember { mutableStateOf(Mode.valueOf(prefs.getString("mode", Mode.STICKS.name)!!)) }
    var limit by remember { mutableIntStateOf(prefs.getInt("limit", 70)) }
    var trim by remember { mutableFloatStateOf(prefs.getFloat("trim", 0f)) }
    var invert by remember { mutableStateOf(prefs.getBoolean("invert", false)) }
    var host by remember { mutableStateOf(prefs.getString("host", "192.168.4.1")!!) }
    var light by remember { mutableStateOf(false) }
    var parked by remember { mutableStateOf(false) }
    var boost by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var throttle by remember { mutableFloatStateOf(0f) }
    var steer by remember { mutableFloatStateOf(0f) }
    var gas by remember { mutableStateOf(false) }
    var reverse by remember { mutableStateOf(false) }
    var neutral by remember { mutableStateOf<Offset?>(null) }
    var out by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(host) { link.host = host }
    LaunchedEffect(mode, limit, trim, invert, host) {
        prefs.edit().putString("mode", mode.name).putInt("limit", limit).putFloat("trim", trim)
            .putBoolean("invert", invert).putString("host", host).apply()
    }
    LaunchedEffect(mode) {
        throttle = 0f; steer = 0f
        neutral = if (mode == Mode.TILT) tiltRaw() else null   // hold the phone the way you like, that becomes "straight"
    }
    LaunchedEffect(status.connected) {
        haptic.performHapticFeedback(if (status.connected) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
    }

    // Control loop: 50 times a second turn the inputs into motor power for the link to send
    LaunchedEffect(Unit) {
        while (true) {
            var t = throttle
            var s = steer
            if (mode == Mode.TILT) {
                val n = neutral ?: Offset.Zero
                val raw = tiltRaw()
                s = ((raw.x - n.x) / 3f).coerceIn(-1f, 1f)
                t = if (gas) 1f else if (reverse) -0.7f else 0f
            }
            if (invert) s = -s
            val top = if (boost) 100 else limit
            out = if (parked) 0 to 0 else Mix.tank(t, s, top, trim)
            link.left = out.first
            link.right = out.second
            delay(20)
        }
    }

    Box(Modifier.fillMaxSize().background(Body)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp)) {
            TopBar(status, mode, light, onMode = { if (it == Mode.TILT && mode == Mode.TILT) neutral = tiltRaw() else mode = it },
                onLight = { light = !light; link.light(light) }, onSettings = { showSettings = true }, openWifi = openWifi)
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    when (mode) {
                        Mode.STICKS -> Labelled("THROTTLE", -2f) {
                            Joystick(Axis.Y, Signal, !parked, Modifier.size(200.dp)) { _, y -> throttle = y }
                        }
                        Mode.ONE -> Text("left thumb's free.\nright stick does both.", color = Dim, fontSize = 14.sp,
                            fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, lineHeight = 20.sp)
                        Mode.TILT -> Labelled("REVERSE", 1.5f) { Pedal(!parked) { reverse = it } }
                    }
                }
                CenterPanel(out, limit, parked, boost, onLimit = { limit = it }, onPark = { parked = !parked }, onBoost = { boost = it })
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    when (mode) {
                        Mode.STICKS -> Labelled("STEER", 2f) {
                            Joystick(Axis.X, Signal, !parked, Modifier.size(200.dp)) { x, _ -> steer = x }
                        }
                        Mode.ONE -> Labelled("DRIVE", 1.5f) {
                            Joystick(Axis.BOTH, Signal, !parked, Modifier.size(220.dp)) { x, y -> steer = x; throttle = y }
                        }
                        Mode.TILT -> Labelled("GAS", -1.5f) { Pedal(!parked) { gas = it } }
                    }
                }
            }
            if (mode == Mode.TILT) {
                Text("tilt like a wheel · tap tilt again to re-centre", color = Dim, fontSize = 12.sp,
                    fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }

    if (showSettings) {
        SettingsDialog(host, trim, invert, onHost = { host = it }, onTrim = { trim = it }, onInvert = { invert = it },
            onClose = { showSettings = false })
    }
}

@Composable
private fun TopBar(status: CarLink.Status, mode: Mode, light: Boolean, onMode: (Mode) -> Unit, onLight: () -> Unit,
                   onSettings: () -> Unit, openWifi: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("rc pilot", color = Ink, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))
        Led(status.connected)
        Spacer(Modifier.width(8.dp))
        if (status.connected) {
            Text("on air · ${status.pingMs} ms", color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            SignalBars(status.rssi)
        } else {
            Text("no car. join wifi RC-Car / drive1234", color = Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Key("wifi", onClick = openWifi)
        }
        Spacer(Modifier.weight(1f))
        ModeSwitch(mode, onMode)
        Spacer(Modifier.width(14.dp))
        Key("light", lit = light, onClick = onLight)
        Spacer(Modifier.width(8.dp))
        Key("setup", onClick = onSettings)
    }
}

/** Little panel LED with a soft glow when it's on. */
@Composable
private fun Led(on: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        if (on) drawCircle(LedOn.copy(alpha = 0.25f), size.minDimension)
        drawCircle(if (on) LedOn else LedOff, size.minDimension / 2f)
        drawCircle(Color.White.copy(alpha = if (on) 0.5f else 0.12f), size.minDimension / 6f, center + Offset(-2f, -2f))
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val bars = when {
        rssi == 0 -> 0
        rssi > -55 -> 4
        rssi > -65 -> 3
        rssi > -75 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.padding(end = 2.dp).width(3.dp).height((3 + i * 3).dp).background(if (i <= bars) Ink else Groove))
    }
}

/** Physical-looking key: flat top, darker lip underneath that disappears when pressed in. */
@Composable
private fun Key(text: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.drawBehind { drawRoundRect(Edge, topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())) }
            .clip(RoundedCornerShape(5.dp)).background(if (lit) Ink else Plate)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (lit) Body else Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Three-position slide switch, like the ones on a real transmitter. */
@Composable
private fun ModeSwitch(mode: Mode, onMode: (Mode) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Edge).padding(3.dp)) {
        Mode.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) Plate else Color.Transparent)
                    .clickable { onMode(m) }.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(m.label.lowercase(), color = if (on) Ink else Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Embossed label-maker strip, stuck on slightly crooked. */
@Composable
private fun DymoLabel(text: String, tilt: Float) {
    Box(Modifier.rotate(tilt).clip(RoundedCornerShape(2.dp)).background(Tape).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = Color(0xFFF2F2F2), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun Labelled(label: String, tilt: Float, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(10.dp))
        DymoLabel(label, tilt)
    }
}

/** Foot pedal: ridged rubber plate that sinks a little while held. */
@Composable
private fun Pedal(enabled: Boolean, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Canvas(
        Modifier.size(width = 140.dp, height = 190.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown()
                down = true; onPress(true)
                waitForUpOrCancellation()
                down = false; onPress(false)
            }
        }
    ) {
        val lip = 6.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
        val sink = if (down) lip else 0f
        drawRoundRect(Edge, Offset(0f, lip), Size(size.width, size.height - lip), r)
        drawRoundRect(if (down) Groove else Plate, Offset(0f, sink), Size(size.width, size.height - lip), r)
        val a = if (enabled) 1f else 0.4f
        var y = sink + 22.dp.toPx()
        while (y < size.height - lip - 18.dp.toPx()) {
            drawLine(Edge.copy(alpha = a), Offset(16.dp.toPx(), y), Offset(size.width - 16.dp.toPx(), y), 4.dp.toPx(), StrokeCap.Round)
            y += 14.dp.toPx()
        }
    }
}

@Composable
private fun CenterPanel(out: Pair<Int, Int>, limit: Int, parked: Boolean, boost: Boolean,
                        onLimit: (Int) -> Unit, onPark: () -> Unit, onBoost: (Boolean) -> Unit) {
    Column(
        Modifier.width(260.dp).fillMaxHeight().padding(vertical = 10.dp).clip(RoundedCornerShape(10.dp))
            .background(Plate).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Gauge((abs(out.first) + abs(out.second)) / 2, out)
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("top speed", color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                Spacer(Modifier.weight(1f))
                Text("$limit", color = Ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Slider(value = limit.toFloat(), onValueChange = { onLimit(it.toInt()) }, valueRange = 30f..100f, steps = 13,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Signal, inactiveTrackColor = Groove,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigKey("boost", "hold", boost, Signal, Modifier.weight(1f).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(); onBoost(true)
                    waitForUpOrCancellation(); onBoost(false)
                }
            })
            BigKey(if (parked) "parked" else "park", if (parked) "tap to drive" else "lock wheels", parked, Ink,
                Modifier.weight(1f).clickable(onClick = onPark))
        }
    }
}

@Composable
private fun BigKey(text: String, sub: String, down: Boolean, color: Color, modifier: Modifier) {
    Box(
        modifier.height(56.dp)
            .drawBehind { drawRoundRect(Edge, topLeft = Offset(0f, 4.dp.toPx()), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())) }
            .offset(y = if (down) 3.dp else 0.dp)
            .clip(RoundedCornerShape(8.dp)).background(if (down) color else Groove),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (down) Body else color, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(sub, color = if (down) Body.copy(alpha = 0.7f) else Dim, fontSize = 10.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
        }
    }
}

/** Old-school needle gauge for average motor power, with the two wheels as small ticks underneath. */
@Composable
private fun Gauge(power: Int, out: Pair<Int, Int>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(180.dp).height(96.dp)) {
            val c = Offset(size.width / 2f, size.height - 6.dp.toPx())
            val r = size.width / 2f - 6.dp.toPx()
            for (i in 0..20) {
                val a = Math.PI * (1 - i / 20.0)
                val long = i % 5 == 0
                val r0 = r - (if (long) 14.dp else 7.dp).toPx()
                val col = if (i >= 16) Signal else Ink.copy(alpha = if (long) 0.9f else 0.45f)
                drawLine(col, c + Offset((r0 * cos(a)).toFloat(), (-r0 * sin(a)).toFloat()),
                    c + Offset((r * cos(a)).toFloat(), (-r * sin(a)).toFloat()), (if (long) 2.5f else 1.5f).dp.toPx())
            }
            val a = Math.PI * (1 - power.coerceIn(0, 100) / 100.0)
            val n = r - 18.dp.toPx()
            drawLine(Signal, c, c + Offset((n * cos(a)).toFloat(), (-n * sin(a)).toFloat()), 2.5f.dp.toPx(), StrokeCap.Round)
            drawCircle(Ink, 5.dp.toPx(), c)
            drawCircle(Body, 2.dp.toPx(), c)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelTick("L", out.first)
            Spacer(Modifier.width(18.dp))
            Text("$power%", color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(18.dp))
            WheelTick("R", out.second)
        }
    }
}

/** One wheel: a short bar that grows right for forward, left for reverse. */
@Composable
private fun WheelTick(label: String, power: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        Canvas(Modifier.width(36.dp).height(6.dp)) {
            drawRect(Groove)
            val mid = size.width / 2f
            val w = mid * abs(power) / 100f
            drawRect(if (power >= 0) Ink else Signal, Offset(if (power >= 0) mid else mid - w, 0f), Size(w, size.height))
            drawRect(Body, Offset(mid - 0.5.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
        }
    }
}

@Composable
private fun SettingsDialog(host: String, trim: Float, invert: Boolean, onHost: (String) -> Unit, onTrim: (Float) -> Unit,
                           onInvert: (Boolean) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Plate,
        shape = RoundedCornerShape(10.dp),
        confirmButton = { TextButton(onClick = onClose) { Text("done", color = Signal, fontFamily = FontFamily.Monospace) } },
        title = { Text("setup", color = Ink, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Car drifts on a straight line? Push trim the other way.", fontSize = 13.sp, color = Dim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("pulls right", fontSize = 11.sp, color = Dim, fontFamily = FontFamily.Monospace)
                    Slider(value = trim, onValueChange = onTrim, valueRange = -0.25f..0.25f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Signal, inactiveTrackColor = Groove))
                    Text("pulls left", fontSize = 11.sp, color = Dim, fontFamily = FontFamily.Monospace)
                }
                Text("trim ${"%+d".format((trim * 100).toInt())}", fontSize = 12.sp, color = Ink, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("flip steering (left turns right?)", modifier = Modifier.weight(1f), fontSize = 13.sp, color = Ink)
                    Switch(checked = invert, onCheckedChange = onInvert)
                }
                OutlinedTextField(value = host, onValueChange = onHost, label = { Text("car address") }, singleLine = true)
            }
        }
    )
}
