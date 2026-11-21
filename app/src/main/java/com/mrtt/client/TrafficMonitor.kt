package com.mrtt.client

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TrafficMonitor {
    data class Sample(val atMs: Long, val downBps: Float, val upBps: Float)

    data class Totals(
        val downBytes: Long = 0,
        val upBytes: Long = 0,
        val peakDownBps: Float = 0f,
        val peakUpBps: Float = 0f,
        val avgDownBps: Float = 0f,
        val avgUpBps: Float = 0f,
        val lastDownBps: Float = 0f,
        val lastUpBps: Float = 0f,
        val activeConnections: Int = 0,
    )

    const val WINDOW_MS = 60_000L
    // Держим чуть больше окна: графику не нужны точки, которых он не рисует,
    // а на слабом телефоне список из двух минут — лишняя работа на каждый кадр.
    private const val KEEP_MS = 70_000L
    private const val FLAT_AFTER_MS = 700L
    private const val SESSION_GAP_MS = 5_000L
    private const val MIN_SAMPLE_MS = 200L
    private const val MAX_ACTIVE_MS = 1_000L
    private const val SPEED_SMOOTHING = 0.45f

    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    private val _totals = MutableStateFlow(Totals())
    val totals: StateFlow<Totals> = _totals.asStateFlow()

    private val _readyWorkers = MutableStateFlow<Set<Int>>(emptySet())
    val readyWorkers: StateFlow<Set<Int>> = _readyWorkers.asStateFlow()

    private var lastAt = 0L
    private var lastUp = 0L
    private var lastDown = 0L
    private var baseUp = 0L
    private var baseDown = 0L
    private var smoothDown = 0f
    private var smoothUp = 0f
    private var peakDown = 0f
    private var peakUp = 0f
    private var activeDownBytes = 0L
    private var activeUpBytes = 0L
    private var activeMs = 0L
    private var lastActive = 0
    private var ticker: kotlinx.coroutines.Job? = null

    @Synchronized
    fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (true) {
                onIdleTick()
                kotlinx.coroutines.delay(250L)
            }
        }
    }

    @Synchronized
    fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    @Synchronized
    fun reset() {
        lastAt = 0L
        lastUp = 0L
        lastDown = 0L
        baseUp = 0L
        baseDown = 0L
        smoothDown = 0f
        smoothUp = 0f
        peakDown = 0f
        peakUp = 0f
        activeDownBytes = 0L
        activeUpBytes = 0L
        activeMs = 0L
        lastActive = 0
        _samples.value = emptyList()
        _totals.value = Totals()
        _readyWorkers.value = emptySet()
    }

    @Synchronized
    fun beginSession(up: Long = 0L, down: Long = 0L, now: Long = SystemClock.elapsedRealtime()) {
        lastAt = now
        lastUp = up
        lastDown = down
        baseUp = up
        baseDown = down
        smoothDown = 0f
        smoothUp = 0f
        peakDown = 0f
        peakUp = 0f
        activeDownBytes = 0L
        activeUpBytes = 0L
        activeMs = 0L
        if (_readyWorkers.value.isNotEmpty()) _readyWorkers.value = emptySet()
        _samples.value = listOf(Sample(now, 0f, 0f))
        publish(now, 0)
    }

    @Synchronized
    fun workerReady(worker: Int) {
        if (worker > 0) _readyWorkers.value = _readyWorkers.value + worker
    }

    @Synchronized
    fun workerNotReady(worker: Int) {
        if (worker > 0 && _readyWorkers.value.contains(worker)) {
            _readyWorkers.value = _readyWorkers.value - worker
        }
    }

    @Synchronized
    fun onTunnelStopped(now: Long = SystemClock.elapsedRealtime()) {
        if (_readyWorkers.value.isNotEmpty()) _readyWorkers.value = emptySet()
        // Хвост графика обнуляем: линия должна упасть в ноль, а не висеть на
        // последней скорости, которая была до выключения.
        smoothDown = 0f
        smoothUp = 0f
        lastAt = 0L
        lastUp = 0L
        lastDown = 0L
        baseUp = 0L
        baseDown = 0L
        peakDown = 0f
        peakUp = 0f
        activeDownBytes = 0L
        activeUpBytes = 0L
        activeMs = 0L
        lastActive = 0
        val fresh = _samples.value.filter { now - it.atMs <= KEEP_MS }
        _samples.value = fresh + Sample(now, 0f, 0f)
        _totals.value = _totals.value.copy(
            lastDownBps = 0f,
            lastUpBps = 0f,
            peakDownBps = 0f,
            peakUpBps = 0f,
            activeConnections = 0,
        )
    }

    @Synchronized
    fun onStats(up: Long, down: Long, active: Int, now: Long = SystemClock.elapsedRealtime()) {
        if (lastAt == 0L || up < lastUp || down < lastDown) {
            // Первое событие или счётчики клиента обнулились (перезапуск
            // сессии внутри процесса): это новый тариф, итоги — с нуля.
            beginSession(up, down, now)
            lastActive = active
            publish(now, active)
            return
        }
        if (now - lastAt > SESSION_GAP_MS) {
            // Приложение было в фоне, туннель за это время переподнялся.
            // Показывать «как было до ухода» нельзя — это прошлый тариф.
            beginSession(up, down, now)
            lastActive = active
            publish(now, active)
            return
        }
        val dtMs = now - lastAt
        if (dtMs <= 0L) return
        val deltaDown = (down - lastDown).coerceAtLeast(0L)
        val deltaUp = (up - lastUp).coerceAtLeast(0L)
        val measuredMs = dtMs.coerceAtLeast(MIN_SAMPLE_MS)
        val rawDown = deltaDown * 1000f / measuredMs
        val rawUp = deltaUp * 1000f / measuredMs
        smoothDown = if (smoothDown == 0f) rawDown else smoothDown + SPEED_SMOOTHING * (rawDown - smoothDown)
        smoothUp = if (smoothUp == 0f) rawUp else smoothUp + SPEED_SMOOTHING * (rawUp - smoothUp)
        if (deltaDown > 0L || deltaUp > 0L) {
            activeDownBytes += deltaDown
            activeUpBytes += deltaUp
            activeMs += minOf(dtMs, MAX_ACTIVE_MS)
        }
        peakDown = maxOf(peakDown, rawDown)
        peakUp = maxOf(peakUp, rawUp)
        lastAt = now
        lastUp = up
        lastDown = down
        val fresh = _samples.value.filter { now - it.atMs <= KEEP_MS } + Sample(now, rawDown, rawUp)
        _samples.value = fresh
        publish(now, active)
    }

    @Synchronized
    fun onAppResumed(now: Long = SystemClock.elapsedRealtime()) {
        if (lastAt == 0L) return
        if (now - lastAt >= FLAT_AFTER_MS) {
            smoothDown = 0f
            smoothUp = 0f
        }
        publish(now, lastActive)
    }

    @Synchronized
    fun onIdleTick(now: Long = SystemClock.elapsedRealtime()) {
        if (lastAt == 0L) return
        val silentFor = now - lastAt
        if (silentFor >= FLAT_AFTER_MS) {
            // Счётчики не менялись — значит трафика нет, и честнее показать ноль.
            smoothDown = 0f
            smoothUp = 0f
            val fresh = _samples.value.filter { now - it.atMs <= KEEP_MS }
            if (fresh.lastOrNull()?.atMs != now) {
                _samples.value = fresh + Sample(now, 0f, 0f)
            }
        }
        publish(now, lastActive)
    }

    private fun publish(now: Long, active: Int) {
        lastActive = active
        val downTotal = (lastDown - baseDown).coerceAtLeast(0L)
        val upTotal = (lastUp - baseUp).coerceAtLeast(0L)
        val activeSeconds = activeMs / 1000f
        _totals.value = Totals(
            downBytes = downTotal,
            upBytes = upTotal,
            peakDownBps = peakDown,
            peakUpBps = peakUp,
            avgDownBps = if (activeSeconds > 0f) activeDownBytes / activeSeconds else 0f,
            avgUpBps = if (activeSeconds > 0f) activeUpBytes / activeSeconds else 0f,
            lastDownBps = smoothDown,
            lastUpBps = smoothUp,
            activeConnections = active,
        )
    }

    fun formatSpeed(bytesPerSecond: Float): String {
        val bits = bytesPerSecond * 8f
        return when {
            bits >= 1_000_000f -> String.format(java.util.Locale.US, "%.1f Мбит/с", bits / 1_000_000f)
            bits >= 1_000f -> String.format(java.util.Locale.US, "%.0f Кбит/с", bits / 1_000f)
            else -> "0 Кбит/с"
        }
    }

    fun formatBytes(bytes: Long): String {
        val value = bytes.toDouble()
        return when {
            value >= 1024.0 * 1024.0 * 1024.0 -> String.format(java.util.Locale.US, "%.2f ГБ", value / (1024.0 * 1024.0 * 1024.0))
            value >= 1024.0 * 1024.0 -> String.format(java.util.Locale.US, "%.1f МБ", value / (1024.0 * 1024.0))
            value >= 1024.0 -> String.format(java.util.Locale.US, "%.0f КБ", value / 1024.0)
            else -> "$bytes Б"
        }
    }
}
