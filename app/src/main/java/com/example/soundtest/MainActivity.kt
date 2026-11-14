package com.example.soundtest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 음악·깜빡이·내비·음량 조절은 전부 백그라운드 서비스가 한다.
        // 그래서 내비 화면을 띄워 이 화면이 가려져도 계속 동작한다. (MulmiPlaybackService.kt)
        ContextCompat.startForegroundService(this, Intent(this, MulmiPlaybackService::class.java))
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                Screen()
            }
        }
    }

    /** 상단 알림(서비스 실행 중 표시)을 띄우기 위한 권한. 거절해도 서비스는 동작한다. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}

/** 화면은 서비스 상태를 보여주고, 버튼 입력을 서비스 설정으로 넘기기만 한다. */
@Composable
fun Screen() {
    val settings by MulmiController.settings.collectAsState()
    val status by MulmiController.status.collectAsState()

    val shownDirection =
        if (settings.automatic) status.cue.direction.label else settings.manualDirection.label

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "곧 방향: $shownDirection", fontSize = 40.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("깜빡이 자동 연동", fontSize = 20.sp)
            Switch(checked = settings.automatic, onCheckedChange = { checked ->
                // 모드 전환 시 이전 방향을 이어받지 않고 가운데부터 시작한다.
                MulmiController.updateSettings {
                    it.copy(automatic = checked, manualDirection = TurnSignal.CENTER)
                }
            })
        }
        Text(status.connectionMessage, fontSize = 18.sp)
        if (settings.automatic) {
            // 테스트 확인용: 예고 판단 결과와 내비 정보
            Text("예고: ${status.cue.mode.label} · 깜빡이 ${status.signal.label}", fontSize = 18.sp)
            Text(status.naviMessage, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("가상 주행 (테스트)", fontSize = 16.sp)
                Switch(checked = settings.simulateDriving, onCheckedChange = { checked ->
                    MulmiController.updateSettings { it.copy(simulateDriving = checked) }
                })
            }
        }
        if (!status.serviceRunning) {
            Text("백그라운드 서비스가 아직 시작되지 않았어요", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "왼쪽 ${(status.leftVolume * 100).roundToInt()}%   |   오른쪽 ${(status.rightVolume * 100).roundToInt()}%",
            fontSize = 28.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(onClick = { MulmiController.updateSettings { it.copy(playing = !it.playing) } }) {
            Text(if (settings.playing) "음악 멈추기" else "음악 재생", fontSize = 24.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row {
            Button(enabled = !settings.automatic, onClick = {
                MulmiController.updateSettings { it.copy(manualDirection = TurnSignal.LEFT) }
            }) {
                Text("좌회전", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(enabled = !settings.automatic, onClick = {
                MulmiController.updateSettings { it.copy(manualDirection = TurnSignal.CENTER) }
            }) {
                Text("직진", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(enabled = !settings.automatic, onClick = {
                MulmiController.updateSettings { it.copy(manualDirection = TurnSignal.RIGHT) }
            }) {
                Text("우회전", fontSize = 24.sp)
            }
        }
    }
}
