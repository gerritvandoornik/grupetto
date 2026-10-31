package com.spop.poverlay.ride

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.SettingsDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Muted = Color(0xFF9EAEC0)
private val Good = Color(0xFF34D399)
private val Waiting = Color(0xFFFBBF24)

/** Saved rides and the Google Drive connection, opened from the Overlay tile. */
@Composable
fun RidesDialog(onDismiss: () -> Unit) {
    val rides by RideStore.rides.collectAsStateWithLifecycle()
    val state by GoogleDrive.state.collectAsStateWithLifecycle()
    val status by GoogleDrive.status.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SettingsDialog("Rides & Google Drive", { GoogleDrive.cancelSignIn(); onDismiss() }) {
        when (val s = state) {
            GoogleDrive.State.NotConfigured -> Text(
                "Google Drive isn't set up in this build (no OAuth client ID). Rides are still saved on the bike.",
                fontSize = 14.sp, color = Waiting)
            GoogleDrive.State.SignedOut -> {
                Text("Upload each ride to a \"Peloton Rides\" folder in your Google Drive.", fontSize = 14.sp)
                OutlinedButton(GoogleDrive::startSignIn, Modifier.fillMaxWidth()) { Text("Connect Google Drive") }
            }
            is GoogleDrive.State.WaitingForApproval -> {
                Text("On your phone or computer, go to", fontSize = 14.sp)
                Text(s.verificationUrl.removePrefix("https://"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("and enter this code:", fontSize = 14.sp)
                Text(s.userCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Good, letterSpacing = 3.sp)
                Text("This screen updates by itself once you approve.", fontSize = 13.sp, color = Muted)
                TextButton(GoogleDrive::cancelSignIn) { Text("Cancel") }
            }
            GoogleDrive.State.Connected -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Google Drive connected · Peloton Rides", fontSize = 14.sp, color = Good, modifier = Modifier.weight(1f))
                TextButton(GoogleDrive::requestUpload) { Text("Upload now") }
                TextButton(GoogleDrive::signOut) { Text("Disconnect") }
            }
        }
        if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = Muted)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            TextButton({
                context.findActivity()?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4711)
                }
            }) { Text("Also save rides to the Download folder") }
        }

        Divider(Modifier.padding(vertical = 10.dp))
        if (rides.isEmpty()) {
            Text("No rides yet. A ride is saved when the timer resets: long-press it, or stop pedalling for 5 minutes. Rides under a minute aren't kept.",
                fontSize = 14.sp, color = Muted)
        } else {
            val format = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rides.take(6).forEach { ride ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(format.format(Date(ride.startMillis)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${DateUtils.formatElapsedTime(ride.timerSeconds.toLong())} · " +
                                "${"%.1f".format(Locale.US, ride.distanceMeters / 1609.344)} mi · " +
                                "${ride.avgPower} W avg · ${ride.calories} kcal",
                                fontSize = 13.sp, color = Muted)
                        }
                        Text(if (ride.uploaded) "In Drive" else "On bike", fontSize = 13.sp,
                            color = if (ride.uploaded) Good else Waiting)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Files: Android/data/com.spop.poverlay/files/rides" +
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) " and Download/Grupetto" else ""),
            fontSize = 12.sp, color = Muted)
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
