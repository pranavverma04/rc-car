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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
            MaterialTheme(colorScheme = darkColorScheme(primary = Dash.Red, background = Dash.Cabin, surface = Dash.Panel,
                surfaceContainerHigh = Dash.Panel)) {
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

@Composable
private fun Cockpit(link: CarLink, prefs: android.content.SharedPreferences, openWifi: () -> Unit) {
    val status by link.status.collectAsState()
    val haptic = LocalHapticFeedback.current

    var limit by remember { mutableIntStateOf(prefs.getInt("limit", 100)) }
    var trim by remember { mutableFloatStateOf(prefs.getFloat("trim", 0f)) }
    var invert by remember { mutableStateOf(prefs.getBoolean("invert", false)) }
    var host by remember { mutableStateOf(prefs.getString("host", "192.168.4.1")!!) }
    var light by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var reverse by remember { mutableStateOf(false) }     // gear: false = D, true = R
    var wheel by remember { mutableFloatStateOf(0f) }     // -1..1
    var gas by remember { mutableFloatStateOf(0f) }       // 0..1 pedal depth
    var brake by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf(0 to 0) }

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
            out = if (brake) 0 to 0 else Mix.car(reverse, gas, w, limit, trim)
            link.left = out.first
            link.right = out.second
            delay(20)
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Dash.Roof, Dash.Cabin, Dash.Floor)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)) {
            StatusStrip(status, light, onLight = { light = !light; link.light(light) }, onSettings = { showSettings = true })
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Steering wheel
                Box(Modifier.weight(1.15f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    SteeringWheel(Modifier.size(270.dp)) { wheel = it }
                }
                // Instrument cluster: speedometer + gear lever
                Row(
                    Modifier.weight(1.1f).fillMaxHeight().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Speedometer(power = (abs(out.first) + abs(out.second)) / 2, reverse = reverse, braking = brake,
                        modifier = Modifier.size(200.dp))
                    Spacer(Modifier.width(18.dp))
                    GearLever(reverse, Modifier.width(92.dp).height(190.dp)) {
                        if (it != reverse) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        reverse = it
                    }
                }
                // Pedals
                Row(
                    Modifier.weight(1f).fillMaxHeight().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BrakePedal(Modifier.width(118.dp).height(140.dp)) { brake = it }
                        PedalLabel("BRAKE", Dash.Red)
                    }
                    Spacer(Modifier.width(22.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GasPedal(Modifier.width(92.dp).height(215.dp)) { gas = it }
                        PedalLabel("GAS", Dash.Green)
                    }
                }
            }
        }
        if (!status.connected) NotConnected(Modifier.align(Alignment.TopCenter).padding(top = 56.dp), openWifi)
    }

    if (showSettings) {
        SettingsDialog(limit, host, trim, invert, onLimit = { limit = it }, onHost = { host = it }, onTrim = { trim = it },
            onInvert = { invert = it }, onClose = { showSettings = false })
    }
}

@Composable
private fun StatusStrip(status: CarLink.Status, light: Boolean, onLight: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (status.connected) Dash.Green else Dash.Red))
        Spacer(Modifier.width(8.dp))
        Text(if (status.connected) "CAR LINKED" else "SEARCHING", color = Dash.White, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(Modifier.width(14.dp))
        Text(if (status.connected) "${status.pingMs} ms" else "-- ms", color = Dash.Grey, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        SignalBars(status.rssi)
        Spacer(Modifier.weight(1f))
        DashButton(if (light) "LIGHT ON" else "LIGHT", light, onLight)
        Spacer(Modifier.width(8.dp))
        DashButton("SETTINGS", false, onSettings)
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
        for (i in 1..4) {
            Box(Modifier.padding(end = 2.dp).width(4.dp).height((4 + i * 3).dp)
                .background(if (i <= bars) Dash.White else Dash.Bezel, RoundedCornerShape(1.dp)))
        }
    }
}

/** Rocker-switch style button, like the ones on a centre console. */
@Composable
private fun DashButton(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2C2E33), Color(0xFF1A1B1F))))
            .border(1.dp, if (on) Dash.Amber else Dash.Bezel, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (on) Dash.Amber else Dash.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun NotConnected(modifier: Modifier, openWifi: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xEE1A1B1F)).border(1.dp, Dash.Amber, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Join Wi-Fi  RC-Car  (password drive1234)", color = Dash.White, fontSize = 13.sp)
        Spacer(Modifier.width(14.dp))
        DashButton("OPEN WI-FI", true, openWifi)
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
