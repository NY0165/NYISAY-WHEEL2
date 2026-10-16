package mz.nyisay.virtualracingwheel

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Single background thread that periodically builds and sends HID reports.
 * Also sends immediately when inputs change (coalesced with the period).
 */
class Sender(
    private val state: ControlState,
    private val send: (ByteArray) -> Boolean
) {
    private val running = AtomicBoolean(false)
    private val reportsSent = AtomicInteger(0)
    @Volatile var hz: Int = 50

    val reportsPerSecond: Int
        get() = reportsSent.getAndSet(0)

    fun start() {
        if (running.getAndSet(true)) return
        thread(name = "hid-sender", isDaemon = true) {
            while (running.get()) {
                val interval = (1000L / hz.coerceIn(10, 100))
                val t0 = System.currentTimeMillis()
                val snap = state.snapshot()
                // Always send at the configured rate so the host sees live axes
                val report = ReportBuilder.build(snap)
                if (send(report)) {
                    reportsSent.incrementAndGet()
                }
                val elapsed = System.currentTimeMillis() - t0
                val sleep = interval - elapsed
                if (sleep > 0) {
                    try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
                }
            }
            // Neutral on stop
            send(ReportBuilder.neutral())
        }
    }

    fun stop() {
        running.set(false)
    }
}
