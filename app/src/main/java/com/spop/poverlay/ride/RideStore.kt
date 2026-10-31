package com.spop.poverlay.ride

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A saved ride, as listed in the Rides dialog. */
data class RideInfo(
    val name: String,
    val fitFile: File,
    val startMillis: Long,
    val timerSeconds: Int,
    val distanceMeters: Double,
    val avgPower: Int,
    val calories: Int,
    val uploaded: Boolean,
)

/**
 * Owns the saved ride files. Each ride is `<name>.fit` plus a `<name>.json` summary; a
 * `<name>.uploaded` marker is written once Google Drive has the file.
 */
object RideStore {
    /** Rides shorter than this are discarded (accidental pedal strokes). */
    const val MinimumRideSeconds = 60

    private lateinit var appContext: Context
    private val mutableRides = MutableStateFlow<List<RideInfo>>(emptyList())
    val rides: StateFlow<List<RideInfo>> = mutableRides.asStateFlow()

    val ridesDir: File
        get() = (appContext.getExternalFilesDir("rides") ?: File(appContext.filesDir, "rides")).apply { mkdirs() }

    /** Raw samples of the ride in progress, so a crash or restart loses nothing. */
    val inProgressFile: File get() = File(appContext.filesDir, "ride_in_progress.csv")

    fun init(context: Context) {
        appContext = context.applicationContext
        recoverInProgress()
        refresh()
    }

    @Synchronized
    fun refresh() {
        val list = ridesDir.listFiles { f -> f.name.endsWith(".fit") }.orEmpty().mapNotNull { fit ->
            val name = fit.name.removeSuffix(".fit")
            val json = File(ridesDir, "$name.json")
            try {
                val o = if (json.exists()) JSONObject(json.readText()) else JSONObject()
                RideInfo(
                    name = name,
                    fitFile = fit,
                    startMillis = o.optLong("start", fit.lastModified()),
                    timerSeconds = o.optInt("timerSeconds", 0),
                    distanceMeters = o.optDouble("distanceMeters", 0.0),
                    avgPower = o.optInt("avgPower", 0),
                    calories = o.optInt("calories", 0),
                    uploaded = File(ridesDir, "$name.uploaded").exists(),
                )
            } catch (e: Exception) {
                Timber.w(e, "Could not read ride summary %s", name)
                null
            }
        }.sortedByDescending { it.startMillis }
        mutableRides.value = list
    }

    fun markUploaded(ride: RideInfo) {
        File(ridesDir, "${ride.name}.uploaded").writeText(System.currentTimeMillis().toString())
        refresh()
    }

    /** Writes the FIT file and summary. Returns null when the ride was too short to keep. */
    @Synchronized
    fun save(samples: List<FitActivityWriter.Sample>): RideInfo? {
        if (samples.size < MinimumRideSeconds) {
            Timber.i("Discarding ride of %d s (too short)", samples.size)
            return null
        }
        val name = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(samples.first().timeMillis)) + "_ride"
        val fit = File(ridesDir, "$name.fit")
        val summary = FileOutputStream(fit).use { FitActivityWriter.write(samples, it) }
        File(ridesDir, "$name.json").writeText(JSONObject().apply {
            put("start", summary.startMillis)
            put("timerSeconds", summary.timerSeconds)
            put("distanceMeters", summary.distanceMeters)
            put("avgPower", summary.avgPower)
            put("calories", summary.calories)
        }.toString())
        copyToDownloads(fit)
        Timber.i("Saved ride %s (%d s)", name, summary.timerSeconds)
        refresh()
        return mutableRides.value.firstOrNull { it.name == name }
    }

    /** Also put a copy in Download/Grupetto so it can be picked from a browser or file manager. */
    private fun copyToDownloads(fit: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fit.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grupetto")
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out -> fit.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Grupetto")
                dir.mkdirs()
                fit.copyTo(File(dir, fit.name), overwrite = true)
            }
        } catch (e: Exception) {
            // Needs storage permission on older Android; the copy in the app folder still exists.
            Timber.w(e, "Could not copy ride to Downloads")
        }
    }

    /** Saves a ride left behind by a crash or restart. */
    private fun recoverInProgress() {
        val file = inProgressFile
        if (!file.exists()) return
        try {
            val samples = file.readLines().mapNotNull { RideRecorder.parseSample(it) }
            file.delete()
            if (samples.isNotEmpty()) save(samples)
        } catch (e: Exception) {
            Timber.w(e, "Could not recover ride in progress")
            file.delete()
        }
    }
}
