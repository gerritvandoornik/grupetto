package com.spop.poverlay.ride

import android.content.Context
import android.content.SharedPreferences
import com.spop.poverlay.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads saved rides to a "Peloton Rides" folder in the rider's Google Drive.
 *
 * The Peloton has no Google Play Services, so sign-in uses Google's OAuth device flow (the one
 * TVs use): the bike shows a short code, the rider approves it on a phone at google.com/device.
 * Only the drive.file scope is requested, so the app can see nothing but the files it creates.
 */
object GoogleDrive {
    sealed class State {
        object NotConfigured : State()
        object SignedOut : State()
        data class WaitingForApproval(val userCode: String, val verificationUrl: String) : State()
        object Connected : State()
    }

    private const val Scope = "https://www.googleapis.com/auth/drive.file"
    private const val FolderName = "Peloton Rides"
    private val RetryInterval = TimeUnit.MINUTES.toMillis(15)

    private val clientId = BuildConfig.GDRIVE_CLIENT_ID
    private val clientSecret = BuildConfig.GDRIVE_CLIENT_SECRET

    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences
    private val uploadLock = Mutex()
    private var signInJob: Job? = null
    private var accessToken: String? = null
    private var accessTokenExpiry = 0L

    private val mutableState = MutableStateFlow<State>(State.NotConfigured)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Last upload result or error, shown under the ride list. */
    private val mutableStatus = MutableStateFlow("")
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    val isConfigured get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("google_drive", Context.MODE_PRIVATE)
        mutableState.value = when {
            !isConfigured -> State.NotConfigured
            refreshToken != null -> State.Connected
            else -> State.SignedOut
        }
        // Retry pending uploads periodically, e.g. after the Wi-Fi was down.
        backgroundScope.launch {
            while (isActive) {
                uploadPending()
                delay(RetryInterval)
            }
        }
    }

    private var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    private var folderId: String?
        get() = prefs.getString("folder_id", null)
        set(value) = prefs.edit().putString("folder_id", value).apply()

    fun requestUpload() {
        backgroundScope.launch { uploadPending() }
    }

    fun signOut() {
        signInJob?.cancel()
        refreshToken = null
        folderId = null
        accessToken = null
        mutableStatus.value = ""
        mutableState.value = if (isConfigured) State.SignedOut else State.NotConfigured
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        if (mutableState.value is State.WaitingForApproval) mutableState.value = State.SignedOut
    }

    /** Starts the device flow; the dialog shows the code while this waits for approval. */
    fun startSignIn() {
        if (!isConfigured) return
        signInJob?.cancel()
        signInJob = backgroundScope.launch {
            try {
                val code = postForm("https://oauth2.googleapis.com/device/code", mapOf(
                    "client_id" to clientId,
                    "scope" to Scope,
                ))
                val deviceCode = code.getString("device_code")
                var interval = code.optLong("interval", 5)
                val expiresAt = System.currentTimeMillis() + code.optLong("expires_in", 1800) * 1000
                val url = code.optString("verification_url", code.optString("verification_uri", "https://www.google.com/device"))
                mutableState.value = State.WaitingForApproval(code.getString("user_code"), url)

                while (isActive && System.currentTimeMillis() < expiresAt) {
                    delay(interval * 1000)
                    val response = postFormRaw("https://oauth2.googleapis.com/token", mapOf(
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "device_code" to deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ))
                    when (response.optString("error")) {
                        "" -> {
                            refreshToken = response.getString("refresh_token")
                            rememberAccessToken(response)
                            mutableState.value = State.Connected
                            mutableStatus.value = "Connected to Google Drive"
                            uploadPending()
                            return@launch
                        }
                        "authorization_pending" -> Unit
                        "slow_down" -> interval += 5
                        else -> {
                            mutableStatus.value = "Sign-in failed: ${response.optString("error")}"
                            mutableState.value = State.SignedOut
                            return@launch
                        }
                    }
                }
                mutableStatus.value = "Sign-in code expired, try again"
                mutableState.value = State.SignedOut
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Google sign-in failed")
                mutableStatus.value = "Sign-in failed: ${e.message}"
                mutableState.value = State.SignedOut
            }
        }
    }

    /** Uploads every saved ride that Drive doesn't have yet. */
    suspend fun uploadPending() = uploadLock.withLock {
        if (!::prefs.isInitialized || refreshToken == null) return@withLock
        val pending = RideStore.rides.value.filter { !it.uploaded }
        if (pending.isEmpty()) return@withLock
        try {
            val token = freshAccessToken() ?: return@withLock
            val folder = ensureFolder(token)
            for (ride in pending) {
                upload(token, folder, ride)
                RideStore.markUploaded(ride)
                mutableStatus.value = "Uploaded ${ride.fitFile.name}"
            }
        } catch (e: Exception) {
            Timber.w(e, "Drive upload failed")
            mutableStatus.value = "Upload waiting: ${e.message ?: "no connection"}"
        }
    }

    private fun freshAccessToken(): String? {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < accessTokenExpiry - 60_000) return cached
        val refresh = refreshToken ?: return null
        val response = postFormRaw("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token",
        ))
        if (response.optString("error") == "invalid_grant") {
            // Access was revoked or expired: ask the rider to connect again.
            signOut()
            mutableStatus.value = "Google Drive access expired, connect again"
            return null
        }
        if (!response.has("access_token")) throw IOException("token refresh failed: ${response.optString("error")}")
        rememberAccessToken(response)
        return accessToken
    }

    private fun rememberAccessToken(response: JSONObject) {
        accessToken = response.getString("access_token")
        accessTokenExpiry = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000
    }

    private fun ensureFolder(token: String): String {
        folderId?.let { id ->
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { r ->
                if (r.isSuccessful && !JSONObject(r.body!!.string()).optBoolean("trashed")) return id
                if (r.code != 404 && !r.isSuccessful) throw IOException("Drive error ${r.code}")
            }
        }
        val body = JSONObject()
            .put("name", FolderName)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        val id = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Could not create folder (${r.code})")
            JSONObject(r.body!!.string()).getString("id")
        }
        folderId = id
        return id
    }

    private fun upload(token: String, folder: String, ride: RideInfo) {
        val metadata = JSONObject()
            .put("name", ride.fitFile.name)
            .put("parents", JSONArray().put(folder))
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata)
            .addPart(ride.fitFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("upload failed (${r.code})")
        }
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val json = postFormRaw(url, fields)
        if (json.has("error")) throw IOException(json.optString("error_description", json.getString("error")))
        return json
    }

    /** Returns the JSON body for both success and OAuth error responses. */
    private fun postFormRaw(url: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(form).build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("HTTP ${r.code}")
            }
        }
    }
}
