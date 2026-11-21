package com.mrtt.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrafficMonitorTest {

    @Before
    fun setUp() {
        TrafficMonitor.reset()
    }

    @After
    fun tearDown() {
        TrafficMonitor.reset()
    }

    private fun totals() = TrafficMonitor.totals.value

    @Test
    fun stoppingTheTunnelTurnsEveryWorkerDotOff() {
        TrafficMonitor.workerReady(1)
        TrafficMonitor.workerReady(4)
        assertEquals(setOf(1, 4), TrafficMonitor.readyWorkers.value)

        TrafficMonitor.onTunnelStopped(now = 20_000)

        // После выключения VPN ни один поток не поднят: точки не должны
        // оставаться светлыми.
        assertTrue(TrafficMonitor.readyWorkers.value.isEmpty())
        assertEquals(0, totals().activeConnections)
        assertEquals(0f, totals().lastDownBps, 1f)
        assertEquals(0f, totals().lastUpBps, 1f)
    }

    @Test
    fun aNewSessionForgetsThePreviousWorkerNumbers() {
        TrafficMonitor.workerReady(2)
        assertEquals(setOf(2), TrafficMonitor.readyWorkers.value)

        TrafficMonitor.beginSession(up = 0, down = 0, now = 30_000)

        // Туннель переподнялся внутри того же процесса — старые номера
        // воркеров больше не означают «поток поднят».
        assertTrue(TrafficMonitor.readyWorkers.value.isEmpty())
    }

    @Test
    fun aWorkerThatDroppedItsStreamIsNoLongerReady() {
        TrafficMonitor.workerReady(3)
        TrafficMonitor.workerNotReady(3)

        assertTrue(TrafficMonitor.readyWorkers.value.isEmpty())

        // Повторный вызов не должен ничего ломать.
        TrafficMonitor.workerNotReady(3)
        TrafficMonitor.workerNotReady(0)
        assertTrue(TrafficMonitor.readyWorkers.value.isEmpty())
    }

    @Test
    fun countsTrafficOfCurrentSession() {
        TrafficMonitor.onStats(up = 1_000, down = 4_000, active = 2, now = 10_000)
        TrafficMonitor.onStats(up = 3_000, down = 12_000, active = 2, now = 11_000)

        assertEquals(8_000L, totals().downBytes)
        assertEquals(2_000L, totals().upBytes)
        assertEquals(8_000f, totals().lastDownBps, 1f)
        assertEquals(2_000f, totals().lastUpBps, 1f)
        assertEquals(2, totals().activeConnections)
    }

    @Test
    fun restartsTotalsWhenCountersResetInsideProcess() {
        TrafficMonitor.onStats(up = 100_000, down = 500_000, active = 3, now = 10_000)
        TrafficMonitor.onStats(up = 120_000, down = 600_000, active = 3, now = 11_000)

        // Клиент перезапустил сессию: счётчики с нуля.
        TrafficMonitor.onStats(up = 200, down = 900, active = 1, now = 12_000)
        TrafficMonitor.onStats(up = 700, down = 2_900, active = 1, now = 13_000)

        assertEquals(2_000L, totals().downBytes)
        assertEquals(500L, totals().upBytes)
    }

    @Test
    fun gapAfterBackgroundStartsFreshSession() {
        TrafficMonitor.onStats(up = 50_000, down = 200_000, active = 2, now = 10_000)
        TrafficMonitor.onStats(up = 60_000, down = 240_000, active = 2, now = 11_000)
        assertTrue(totals().downBytes > 0)

        // Приложение было в фоне: туннель успел переподняться, счётчики
        // выросли, но это уже другой тариф.
        TrafficMonitor.onStats(up = 80_000, down = 320_000, active = 2, now = 120_000)
        assertEquals(0L, totals().downBytes)
        assertEquals(0L, totals().upBytes)

        TrafficMonitor.onStats(up = 81_000, down = 322_000, active = 2, now = 121_000)
        assertEquals(2_000L, totals().downBytes)
        assertEquals(1_000L, totals().upBytes)
    }

    @Test
    fun idleTickDropsSpeedToZeroWithoutNewStats() {
        TrafficMonitor.onStats(up = 0, down = 0, active = 1, now = 10_000)
        TrafficMonitor.onStats(up = 0, down = 5_000, active = 1, now = 10_500)
        assertTrue(totals().lastDownBps > 0f)

        TrafficMonitor.onIdleTick(now = 10_600)
        assertTrue(totals().lastDownBps > 0f)

        TrafficMonitor.onIdleTick(now = 11_500)
        assertEquals(0f, totals().lastDownBps, 0f)
        assertEquals(0f, totals().lastUpBps, 0f)
    }

    @Test
    fun tinyStatIntervalsDoNotTurnSmallBurstsIntoGigabitSpeeds() {
        TrafficMonitor.onStats(up = 0, down = 0, active = 1, now = 10_000)
        TrafficMonitor.onStats(up = 0, down = 4_000, active = 1, now = 10_010)

        assertTrue("speed=${totals().lastDownBps}", totals().lastDownBps <= 20_000f)
        assertTrue(totals().lastDownBps > 0f)
    }

    @Test
    fun averageCountsOnlyPeriodsWithTraffic() {
        TrafficMonitor.onStats(up = 0, down = 0, active = 1, now = 10_000)
        TrafficMonitor.onStats(up = 0, down = 10_000, active = 1, now = 11_000)
        TrafficMonitor.onStats(up = 0, down = 10_000, active = 1, now = 14_000)

        assertEquals(10_000f, totals().avgDownBps, 1f)
    }

    @Test
    fun historyStaysBoundedAndKeepsSessionCurve() {
        var now = 1_000L
        repeat(200) {
            TrafficMonitor.onStats(up = it * 10L, down = it * 40L, active = 1, now = now)
            now += 1_000L
        }
        val samples = TrafficMonitor.samples.value
        assertTrue("история не должна расти бесконечно: ${samples.size}", samples.size <= 80)
        assertTrue(samples.zipWithNext().all { (a, b) -> a.atMs <= b.atMs })
    }
}
