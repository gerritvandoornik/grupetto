package com.spop.poverlay.ride

import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.BufferedWriter
import java.io.FileWriter
import kotlin.math.roundToInt

/**
 * Records one sample per second while Grupetto's ride timer runs, and saves the ride as a FIT
 * file when the timer is reset (long press, or 5 minutes without pedalling) or the overlay stops.
 * Samples are also appended to a file as they arrive so a crash does not lose the ride.
 */
class RideRecorder(
    private val sensorInterface: SensorInterface,
    private val elapsedSeconds: StateFlow<Long>,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MphToMps = 0.44704f

        fun formatSample(s: FitActivityWriter.Sample) =
            "${s.timeMillis},${s.powerWatts},${s.cadenceRpm},${s.speedMps},${s.heartRateBpm}"

        fun parseSample(line: String): FitActivityWriter.Sample? {
            val p = line.split(',')
            if (p.size != 5) return null
            return try {
                FitActivityWriter.Sample(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toFloat(), p[4].toInt())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    @Volatile private var power = 0f
    @Volatile private var cadence = 0f
    @Volatile private var speedMph = 0f
    @Volatile private var heartRate = 0

    private val lock = Mutex()
    private val samples = ArrayList<FitActivityWriter.Sample>()
    private var writer: BufferedWriter? = null
    private var lastElapsed = 0L

    fun start() {
        scope.launch(Dispatchers.Default) {
            combine(
                sensorInterface.power,
                sensorInterface.cadence,
                sensorInterface.speed,
                HeartRateManager.heartRate,
            ) { p, c, s, hr ->
                power = p; cadence = c; speedMph = s; heartRate = hr ?: 0
            }.collect { }
        }
        scope.launch(Dispatchers.IO) {
            elapsedSeconds.collect { elapsed -> onTick(elapsed) }
        }
    }

    private suspend fun onTick(elapsed: Long) = lock.withLock {
        when {
            elapsed > lastElapsed -> addSample()
            elapsed == 0L && lastElapsed > 0L -> finishLocked()
        }
        lastElapsed = elapsed
    }

    private fun addSample() {
        val sample = FitActivityWriter.Sample(
            System.currentTimeMillis(),
            power.coerceAtLeast(0f).roundToInt(),
            cadence.coerceAtLeast(0f).roundToInt(),
            speedMph.coerceAtLeast(0f) * MphToMps,
            heartRate,
        )
        samples.add(sample)
        try {
            val w = writer ?: BufferedWriter(FileWriter(RideStore.inProgressFile, true)).also { writer = it }
            w.write(formatSample(sample))
            w.newLine()
            if (samples.size % 10 == 0) w.flush()
        } catch (e: Exception) {
            Timber.w(e, "Could not write ride checkpoint")
        }
    }

    private fun finishLocked() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        val ride = ArrayList(samples)
        samples.clear()
        RideStore.inProgressFile.delete()
        if (ride.isEmpty()) return
        try {
            if (RideStore.save(ride) != null) GoogleDrive.requestUpload()
        } catch (e: Exception) {
            Timber.e(e, "Could not save ride")
        }
    }

    /** Saves the current ride, if any. Called when the overlay service stops. */
    fun finish() {
        // Runs outside the overlay scope, which is being cancelled.
        GoogleDrive.backgroundScope.launch(Dispatchers.IO) {
            lock.withLock {
                finishLocked()
                lastElapsed = 0L
            }
        }
    }
}
