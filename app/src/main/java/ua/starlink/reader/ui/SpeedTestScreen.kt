package ua.starlink.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.starlink.reader.R
import ua.starlink.reader.SpeedTestPhase
import ua.starlink.reader.SpeedTestUiState

/**
 * Тест швидкості інтернету через поточне Wi-Fi (тарілку) — окремо від
 * зчитування комплекту, тому власний, простий екран без кроків і QR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(state: SpeedTestUiState, onStart: () -> Unit, onBack: () -> Unit) {
    val running = state.phase == SpeedTestPhase.PING ||
        state.phase == SpeedTestPhase.DOWNLOAD ||
        state.phase == SpeedTestPhase.UPLOAD
    val finished = state.phase == SpeedTestPhase.DONE || state.phase == SpeedTestPhase.ERROR

    Scaffold(
        containerColor = Brand.Ink,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.speed_test_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        RowIcon(R.drawable.ic_back, Brand.TextPrimary, size = 20)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SpeedRow(
                        label = stringResource(R.string.speed_test_ping_label),
                        value = state.pingMs?.let { stringResource(R.string.speed_test_ms_value, it) },
                        active = state.phase == SpeedTestPhase.PING,
                    )
                    SpeedRow(
                        label = stringResource(R.string.speed_test_download_label),
                        value = state.downloadMbps?.let {
                            stringResource(R.string.speed_test_mbps_value, it)
                        },
                        active = state.phase == SpeedTestPhase.DOWNLOAD,
                    )
                    SpeedRow(
                        label = stringResource(R.string.speed_test_upload_label),
                        value = state.uploadMbps?.let {
                            stringResource(R.string.speed_test_mbps_value, it)
                        },
                        active = state.phase == SpeedTestPhase.UPLOAD,
                    )
                }
            }

            if (state.phase == SpeedTestPhase.ERROR && state.error != null) {
                Text(state.error, color = Brand.Alert, style = MaterialTheme.typography.bodyMedium)
            }

            Text(
                stringResource(R.string.speed_test_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextMuted,
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onStart,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Accent,
                    contentColor = Color(0xFF04070E),
                ),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF04070E),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(runningLabel(state.phase), style = MaterialTheme.typography.titleMedium)
                } else {
                    RowIcon(R.drawable.ic_speed, Color(0xFF04070E), size = 20)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (finished) R.string.speed_test_retry else R.string.speed_test_start),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun runningLabel(phase: SpeedTestPhase): String = stringResource(
    when (phase) {
        SpeedTestPhase.PING -> R.string.speed_test_running_ping
        SpeedTestPhase.DOWNLOAD -> R.string.speed_test_running_download
        SpeedTestPhase.UPLOAD -> R.string.speed_test_running_upload
        else -> R.string.speed_test_start
    }
)

@Composable
private fun SpeedRow(label: String, value: String?, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(R.drawable.ic_speed, if (active) Brand.Accent else Brand.TextMuted, size = 22)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.TextMuted,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Brand.Accent)
        } else {
            Text(value ?: stringResource(R.string.value_not_read), style = MonoValue, color = Brand.TextPrimary)
        }
    }
}
