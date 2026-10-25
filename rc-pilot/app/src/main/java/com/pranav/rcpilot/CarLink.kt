package com.pranav.rcpilot

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Talks to the car over UDP, like ESP-Drone talks to its drone.
 * Sends "D <seq> <left> <right>" 25 times a second, even when still, so the car's
 * 400 ms failsafe never trips while the app is open and every reply measures the ping.
 */
class CarLink(private val scope: CoroutineScope) {
    data class Status(val connected: Boolean = false, val pingMs: Int = -1, val rssi: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Volatile var left = 0
    @Volatile var right = 0
    @Volatile var host = "192.168.4.1"
    /** The Wi-Fi network to send on. Without this, Android may push packets out over mobile data. */
    @Volatile var network: Network? = null

    private var socket: DatagramSocket? = null
    private var boundTo: Network? = null
    private var jobs = listOf<Job>()
    private val sentAt = LongArray(64)
    @Volatile private var lastAckMs = 0L

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(scope.launch(Dispatchers.IO) { sendLoop() }, scope.launch(Dispatchers.IO) { receiveLoop() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val s = socket
        socket = null
        boundTo = null
        // Stop the wheels right away instead of waiting for the failsafe (network calls are not allowed on the UI thread)
        Thread {
            runCatching { s?.let { sendOn(it, "D 0 0 0") } }
            s?.close()
        }.start()
        _status.value = Status()
    }

    fun light(on: Boolean) = scope.launch(Dispatchers.IO) {
        repeat(3) { runCatching { send("L ${if (on) 1 else 0}") }; delay(30) }
    }

    private fun openSocket(): DatagramSocket? {
        val net = network
        if (socket != null && boundTo == net) return socket
        socket?.close()
        socket = runCatching {
            DatagramSocket().apply {
                soTimeout = 250
                net?.bindSocket(this)
            }
        }.getOrNull()
        boundTo = net
        return socket
    }

    private fun send(text: String) {
        openSocket()?.let { sendOn(it, text) }
    }

    private fun sendOn(s: DatagramSocket, text: String) {
        val bytes = text.toByteArray()
        s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), PORT))
    }

    private suspend fun sendLoop() {
        var seq = 0L
        while (scope.isActive) {
            seq++
            sentAt[(seq % sentAt.size).toInt()] = System.nanoTime()
            runCatching { send("D $seq $left $right") }
            val alive = System.currentTimeMillis() - lastAckMs < 1000
            if (!alive && _status.value.connected) _status.value = Status()
            delay(40)
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(64)
        while (scope.isActive) {
            val s = socket
            if (s == null || s.isClosed) { Thread.sleep(50); continue }
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val parts = String(p.data, 0, p.length).trim().split(' ')
                if (parts.size >= 3 && parts[0] == "A") {
                    val seq = parts[1].toLongOrNull() ?: continue
                    val ping = ((System.nanoTime() - sentAt[(seq % sentAt.size).toInt()]) / 1_000_000).toInt()
                    lastAckMs = System.currentTimeMillis()
                    _status.value = Status(true, ping.coerceAtLeast(1), parts[2].toIntOrNull() ?: 0)
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
    }

    companion object {
        const val PORT = 4210
    }
}
