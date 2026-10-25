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

private val Bg = Color(0xFF0B0F14)
private val Panel = Color(0xFF141B24)
private val Line = Color(0xFF243041)
private val Amber = Color(0xFFF5A524)
private val Green = Color(0xFF34D399)
private val Red = Color(0xFFF87171)
private val Muted = Color(0xFF8A97A8)

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
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Bg, surface = Panel)) {
                Cockpit(
                    link = link,
                    prefs = prefs,
                    tiltRaw = { tiltRaw.value },
                    openWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                )
            }
        }
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

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp)) {
            TopBar(status, mode, light, onMode = { if (it == Mode.TILT && mode == Mode.TILT) neutral = tiltRaw() else mode = it }, onLight = { light = !light; link.light(light) },
                onSettings = { showSettings = true })
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Left side
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    when (mode) {
                        Mode.STICKS -> StickWithLabel("THROTTLE") {
                            Joystick(Axis.Y, Amber, !parked, Modifier.size(210.dp)) { _, y -> throttle = y }
                        }
                        Mode.ONE -> Hint("One-stick mode\nRight stick drives and steers")
                        Mode.TILT -> Pedal("REVERSE", Red, !parked) { reverse = it }
                    }
                }
                CenterPanel(out, limit, parked, boost, status.connected,
                    onLimit = { limit = it }, onPark = { parked = !parked }, onBoost = { boost = it })
                // Right side
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    when (mode) {
                        Mode.STICKS -> StickWithLabel("STEER") {
                            Joystick(Axis.X, Amber, !parked, Modifier.size(210.dp)) { x, _ -> steer = x }
                        }
                        Mode.ONE -> StickWithLabel("DRIVE") {
                            Joystick(Axis.BOTH, Amber, !parked, Modifier.size(230.dp)) { x, y -> steer = x; throttle = y }
                        }
                        Mode.TILT -> Pedal("GAS", Green, !parked) { gas = it }
                    }
                }
            }
            if (mode == Mode.TILT) {
                Text("Tilt the phone like a steering wheel. Tap Tilt again to reset straight.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (!status.connected) NotConnected(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), openWifi)
    }

    if (showSettings) {
        SettingsDialog(host, trim, invert, onHost = { host = it }, onTrim = { trim = it }, onInvert = { invert = it },
            onClose = { showSettings = false })
    }
}

@Composable
private fun TopBar(status: CarLink.Status, mode: Mode, light: Boolean, onMode: (Mode) -> Unit, onLight: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (status.connected) Green else Red))
        Spacer(Modifier.width(8.dp))
        Text(if (status.connected) "CONNECTED" else "SEARCHING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(14.dp))
        Text(if (status.connected) "${status.pingMs} ms" else "-- ms", color = pingColor(status), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.width(14.dp))
        SignalBars(status.rssi)
        if (status.rssi != 0) Text(" ${status.rssi} dBm", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Mode.entries.forEach { m ->
            Chip(m.label, m == mode) { onMode(m) }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(10.dp))
        Chip(if (light) "LIGHT ON" else "LIGHT", light, onLight)
        Spacer(Modifier.width(6.dp))
        Chip("SETTINGS", false, onSettings)
    }
}

private fun pingColor(s: CarLink.Status) = when {
    !s.connected -> Muted
    s.pingMs < 40 -> Green
    s.pingMs < 100 -> Amber
    else -> Red
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
        for (i in 1..4) {
            Box(Modifier.padding(end = 2.dp).width(4.dp).height((4 + i * 3).dp)
                .background(if (i <= bars) Green else Line, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Amber else Panel)
            .border(1.dp, if (on) Amber else Line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StickWithLabel(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun Pedal(label: String, color: Color, enabled: Boolean, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.size(width = 150.dp, height = 190.dp).clip(RoundedCornerShape(22.dp))
            .background(if (down) color else color.copy(alpha = if (enabled) 0.18f else 0.06f))
            .border(2.dp, color.copy(alpha = if (enabled) 0.8f else 0.2f), RoundedCornerShape(22.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    down = true; onPress(true)
                    waitForUpOrCancellation()
                    down = false; onPress(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (down) Color.Black else Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun CenterPanel(out: Pair<Int, Int>, limit: Int, parked: Boolean, boost: Boolean, connected: Boolean,
                        onLimit: (Int) -> Unit, onPark: () -> Unit, onBoost: (Boolean) -> Unit) {
    Column(
        Modifier.width(250.dp).fillMaxHeight().padding(vertical = 8.dp).clip(RoundedCornerShape(20.dp))
            .background(Panel).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Live motor power, left and right
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MotorBar("L", out.first)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.CenterVertically)) {
                val avg = (abs(out.first) + abs(out.second)) / 2
                Text("$avg", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text("% POWER", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            MotorBar("R", out.second)
        }
        // Speed limit
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text("MAX SPEED", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text("$limit%", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Slider(value = limit.toFloat(), onValueChange = { onLimit(it.toInt()) }, valueRange = 30f..100f, steps = 13,
                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Line))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Hold for full power
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (boost) Amber else Bg).border(1.dp, Amber, RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(); onBoost(true)
                            waitForUpOrCancellation(); onBoost(false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("BOOST", color = if (boost) Color.Black else Amber, fontWeight = FontWeight.Black) }
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (parked) Red else Bg).border(1.dp, Red, RoundedCornerShape(14.dp))
                    .clickable(onClick = onPark),
                contentAlignment = Alignment.Center
            ) { Text(if (parked) "PARKED" else "PARK", color = if (parked) Color.Black else Red, fontWeight = FontWeight.Black) }
        }
        if (!connected) Text("Wheels stop by themselves\nwhen the link drops", color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun MotorBar(label: String, power: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
        Box(Modifier.width(18.dp).weight(1f).clip(RoundedCornerShape(6.dp)).background(Bg)) {
            // Fills up from the middle line for forward, down for reverse
            val frac = abs(power) / 100f
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line).align(Alignment.Center))
            when {
                power > 0 -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f - 0.5f * frac + 0.0001f))
                    Box(Modifier.fillMaxWidth().weight(0.5f * frac + 0.0001f).background(Green))
                    Spacer(Modifier.weight(0.5f))
                }
                power < 0 -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(0.5f))
                    Box(Modifier.fillMaxWidth().weight(0.5f * frac + 0.0001f).background(Red))
                    Spacer(Modifier.weight(1f - 0.5f * frac + 0.0001f))
                }
            }
        }
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NotConnected(modifier: Modifier, openWifi: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A1416)).border(1.dp, Red, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Join Wi-Fi  RC-Car  (password drive1234)", color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(14.dp))
        Chip("OPEN WI-FI", true, openWifi)
    }
}

@Composable
private fun SettingsDialog(host: String, trim: Float, invert: Boolean, onHost: (String) -> Unit, onTrim: (Float) -> Unit,
                           onInvert: (Boolean) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("DONE") } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Trim: if the car pulls to one side while driving straight, slide towards the other side.",
                    fontSize = 12.sp, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pulls right", fontSize = 11.sp, color = Muted)
                    Slider(value = trim, onValueChange = onTrim, valueRange = -0.25f..0.25f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("Pulls left", fontSize = 11.sp, color = Muted)
                }
                Text("Trim ${"%+d".format((trim * 100).toInt())}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flip steering (if left turns right)", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = invert, onCheckedChange = onInvert)
                }
                OutlinedTextField(value = host, onValueChange = onHost, label = { Text("Car address") }, singleLine = true)
            }
        }
    )
}
