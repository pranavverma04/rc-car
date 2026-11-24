package com.pranav.rcpilot

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val link by lazy { CarLink(lifecycleScope) }
    private lateinit var cm: ConnectivityManager

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
        val prefs = getSharedPreferences("rc", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Dash.Red, background = Dash.Panel, surface = Color(0xFF1C1D21),
                surfaceContainerHigh = Color(0xFF1C1D21))) {
                Cockpit(link, prefs, openWifi = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
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
        link.start()
    }

    override fun onStop() {
        link.stop()   // app in background: wheels stop
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        super.onStop()
    }
}

private val HudShadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 6f)

@Composable
private fun Cockpit(link: CarLink, prefs: android.content.SharedPreferences, openWifi: () -> Unit) {
    val status by link.status.collectAsState()
    val haptic = LocalHapticFeedback.current

    var limit by remember { mutableIntStateOf(prefs.getInt("limit", 100)) }
    var trim by remember { mutableFloatStateOf(prefs.getFloat("trim", 0f)) }
    var invert by remember { mutableStateOf(prefs.getBoolean("invert", false)) }
    var host by remember { mutableStateOf(prefs.getString("host", "192.168.4.1")!!) }
    var showSettings by remember { mutableStateOf(false) }

    var gear by remember { mutableStateOf(Gear.D) }
    var wheel by remember { mutableFloatStateOf(0f) }     // -1..1
    var gas by remember { mutableFloatStateOf(0f) }       // 0..1 pedal depth
    var brake by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf(0 to 0) }
    var hazard by remember { mutableStateOf(false) }
    var blink by remember { mutableStateOf(false) }

    LaunchedEffect(host) { link.host = host }
    LaunchedEffect(limit, trim, invert, host) {
        prefs.edit().putInt("limit", limit).putFloat("trim", trim).putBoolean("invert", invert).putString("host", host).apply()
    }
    LaunchedEffect(status.connected) {
        haptic.performHapticFeedback(if (status.connected) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
    }

    // Control loop: 50 times a second turn wheel + gear + pedals into motor power for the link to send
    LaunchedEffect(Unit) {
        while (true) {
            val w = if (invert) -wheel else wheel
            out = Mix.drive(gear, brake, gas, w, limit, trim)
            link.left = out.first
            link.right = out.second
            delay(20)
        }
    }

    // Hazards blink the triangle, both arrows and the LED on the car
    LaunchedEffect(hazard) {
        if (hazard) {
            while (true) {
                blink = !blink
                link.light(blink)
                delay(450)
            }
        } else {
            blink = false
            link.light(false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dash.Panel)) {
        val sw = maxWidth
        val sh = maxHeight
        /** Place by share of the screen, like the game's fixed layout. */
        fun at(x: Float, y: Float, w: Dp, h: Dp) = Modifier.offset(sw * x, sh * y).size(w, h)
        /** Same, but x and y give the centre. */
        fun centred(x: Float, y: Float, w: Dp, h: Dp) = Modifier.offset(sw * x - w / 2, sh * y - h / 2).size(w, h)

        val signed = (out.first + out.second) / 200f
        RoadScene(Modifier.fillMaxSize(), speed = { (out.first + out.second) / 200f }, steer = { wheel })

        // Windshield overlay
        Box(at(0.012f, 0.02f, sh * 0.19f, sh * 0.125f).clip(RoundedCornerShape(8.dp)).clickable { showSettings = true }) {
            SettingsIcon(Modifier.fillMaxSize())
        }
        DriveTimer(moving = signed != 0f, modifier = Modifier.offset(sw * 0.15f, sh * 0.005f))
        Text("Gas", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            style = TextStyle(shadow = HudShadow), modifier = Modifier.offset(sw * 0.026f, sh * 0.185f))
        GasBar({ gas }, at(0.044f, 0.26f, 12.dp, sh * 0.39f))
        if (status.connected) LinkTag(status, Modifier.offset(sw * 0.33f, sh * 0.035f))

        // Dash
        DashboardBody(Modifier.fillMaxSize())

        val dial = sh * 0.275f
        Speedometer((abs(out.first) + abs(out.second)) / 2, centred(0.325f, 0.85f, dial, dial))
        SignalGauge(status.rssi, centred(0.665f, 0.85f, dial, dial))
        val arrow = sh * 0.085f
        TurnLamp(-1, if (hazard) blink else wheel < -0.12f, centred(0.415f, 0.775f, arrow * 1.2f, arrow))
        TurnLamp(1, if (hazard) blink else wheel > 0.12f, centred(0.58f, 0.775f, arrow * 1.2f, arrow))
        HazardButton(if (hazard) blink else false, centred(0.4975f, 0.775f, sh * 0.15f, sh * 0.14f)) { hazard = !hazard }

        // Pedals
        BrakePedal(at(0.018f, 0.755f, sw * 0.092f, sh * 0.225f)) {
            brake = it
            if (it) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        GasPedal(at(0.125f, 0.615f, sw * 0.095f, sh * 0.375f)) { gas = it }

        // Gear gate above the wheel
        GearLever(gear, at(0.862f, 0f, sw * 0.112f, sh * 0.53f)) {
            if (it != gear) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            gear = it
        }
        val wheelD = sh * 0.56f
        SteeringWheel(centred(0.865f, 0.785f, wheelD, wheelD)) { wheel = it }

        if (!status.connected) NotConnected(Modifier.offset(sw * 0.30f, sh * 0.03f), openWifi)
    }

    if (showSettings) {
        SettingsDialog(limit, host, trim, invert, onLimit = { limit = it }, onHost = { host = it }, onTrim = { trim = it },
            onInvert = { invert = it }, onClose = { showSettings = false })
    }
}

/** Time actually spent moving since the app opened, shown like a lap timer. */
@Composable
private fun DriveTimer(moving: Boolean, modifier: Modifier = Modifier) {
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(moving) {
        var last = System.currentTimeMillis()
        while (moving) {
            delay(30)
            val now = System.currentTimeMillis()
            ms += now - last
            last = now
        }
    }
    val text = "%d:%02d.%02d".format(ms / 60000, ms / 1000 % 60, ms / 10 % 100)
    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 34.sp,
        style = TextStyle(shadow = HudShadow), modifier = modifier)
}

@Composable
private fun LinkTag(status: CarLink.Status, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(Dash.Green))
        Spacer(Modifier.width(7.dp))
        Text("LINKED  ${status.pingMs} ms", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = HudShadow))
    }
}

@Composable
private fun NotConnected(modifier: Modifier, openWifi: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xEE1A1B1F)).border(1.dp, Dash.Amber, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Join Wi-Fi  RC-Car  (password drive1234)", color = Dash.White, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2E33)).border(1.dp, Dash.Amber, RoundedCornerShape(8.dp))
                .clickable(onClick = openWifi).padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text("OPEN WI-FI", color = Dash.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun SettingsDialog(limit: Int, host: String, trim: Float, invert: Boolean, onLimit: (Int) -> Unit, onHost: (String) -> Unit,
                           onTrim: (Float) -> Unit, onInvert: (Boolean) -> Unit, onClose: () -> Unit) {
    val sliderColors = SliderDefaults.colors(thumbColor = Dash.White, activeTrackColor = Dash.Red, inactiveTrackColor = Dash.Bezel,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("DONE", color = Dash.White) } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text("Top speed (full gas)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("$limit%", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(value = limit.toFloat(), onValueChange = { onLimit(it.toInt()) }, valueRange = 30f..100f, colors = sliderColors)
                Text("Trim: if the car pulls to one side while driving straight, slide towards the other side.",
                    fontSize = 12.sp, color = Dash.Grey)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pulls right", fontSize = 11.sp, color = Dash.Grey)
                    Slider(value = trim, onValueChange = onTrim, valueRange = -0.25f..0.25f, colors = sliderColors,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("Pulls left", fontSize = 11.sp, color = Dash.Grey)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flip steering (if left turns right)", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = invert, onCheckedChange = onInvert)
                }
                OutlinedTextField(value = host, onValueChange = onHost, label = { Text("Car address") }, singleLine = true)
            }
        }
    )
}
