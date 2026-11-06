package com.example.soundtest

import android.media.MediaPlayer
import kotlin.math.roundToInt
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Screen(lifecycle)
            }
        }
    }
}

@Composable
fun Screen(lifecycle: Lifecycle) {
    val context = LocalContext.current

    // 음악 플레이어 준비 (raw 폴더의 calm_music.mp3를 반복 재생)
    val player = remember {
        MediaPlayer.create(context, R.raw.calm_music).apply { isLooping = true }
    }
    // 앱 꺼질 때 플레이어 정리
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf("가운데") }

    // 쏠림 정도: -1 = 완전 왼쪽, 0 = 가운데, +1 = 완전 오른쪽
    var target by remember { mutableFloatStateOf(0f) }
    val strength = 0.6f   // 방향 애니메이션 목표값. 실제 음량은 아래에서 70%/30%로 계산.

    // 자동 모드에서는 실제 콘솔 깜빡이를 사용한다. 수동 모드는 기존 버튼 테스트용이다.
    // 두 입력이 서로 덮어쓰지 않도록 한 번에 한 모드만 음량을 결정한다.
    var automatic by remember { mutableStateOf(true) }
    var signal by remember { mutableStateOf(TurnSignal.CENTER) }
    var connectionMessage by remember { mutableStateOf("깜빡이 연결 중") }
    val signalClient = remember { PleosTurnSignalClient(context.applicationContext) }
    // 내비 회전 정보 + 깜빡이를 합쳐 최종 예고 방향을 정한다 (TurnCueEngine.kt)
    val naviClient = remember { PleosNaviClient(context.applicationContext) }
    var cue by remember { mutableStateOf(TurnCue.IDLE) }
    var naviMessage by remember { mutableStateOf("내비 대기") }
    // [테스트용] 에뮬레이터에서 차가 안 움직이므로 남은 거리를 앱 안에서 줄인다 (DrivingSimulator.kt)
    var simulateDriving by remember { mutableStateOf(false) }

    // 자동 모드 + 화면이 보이는 동안만 공식 SDK 변경 알림을 구독한다.
    // 이전의 HTTP 요청/JSON 처리/250ms 조회 반복문은 더 이상 사용하지 않는다.
    // collect는 메인 스레드에서 실행되어 화면 상태와 음량이 함께 갱신된다.
    LaunchedEffect(lifecycle, automatic, simulateDriving) {
        if (!automatic) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                signal = TurnSignal.CENTER
                cue = TurnCue.IDLE
                connectionMessage = "Vehicle SDK 연결 중"
                val signals = signalClient.observe()
                    .onEach { snapshot ->
                        signal = snapshot.signal
                        connectionMessage = snapshot.message
                    }
                    .map { it.signal }
                val realNavi = naviClient.observe()
                val navi = (if (simulateDriving) realNavi.withSimulatedDriving() else realNavi)
                    .onEach { naviMessage = it.message }
                val speeds = flowOf(if (simulateDriving) SIM_SPEED_MPS else null)
                turnCues(signals, navi, speeds).collect { cue = it }
            } finally {
                // 화면을 떠나거나 수동 모드로 전환하면 구독이 취소되고 연결도 정리된다.
                signal = TurnSignal.CENTER
                cue = TurnCue.IDLE
                connectionMessage = "Vehicle SDK 감시 대기"
                naviMessage = "내비 대기"
            }
        }
    }

    // 자동 모드에서는 깜빡이 원본(signal)이 아니라 예고 판단 결과(cue)로 음량을 정한다.
    // 회전 예고는 회전 끝날 때까지, 차선변경은 2초만 쏠린다. CENTER면 양쪽 100%.
    val effectiveTarget = if (automatic) {
        when (cue.direction) {
            TurnSignal.LEFT -> -strength
            TurnSignal.RIGHT -> strength
            TurnSignal.CENTER -> 0f
        }
    } else target
    val effectiveDirection = if (automatic) cue.direction.label else direction

    // target이 바뀌면 0.8초 동안 부드럽게 따라감
    val balance by animateFloatAsState(
        targetValue = effectiveTarget,
        animationSpec = tween(durationMillis = 800),
        label = "balance"
    )

    // -1(왼쪽)~+1(오른쪽)로 정규화해 실제 재생 음량과 화면 표시를 함께 결정한다.
    // 가운데(0): 100%/100%, 왼쪽(-1): 70%/30%, 오른쪽(+1): 30%/70%. (팀 확정 세기 7:3)
    // 전환 중에는 기존 0.8초 애니메이션을 따라 두 채널이 부드럽게 변한다.
    val normalizedBalance = (balance / strength).coerceIn(-1f, 1f)
    val turnAmount = kotlin.math.abs(normalizedBalance)
    val leftVol = 1f - 0.3f * turnAmount - 0.4f * maxOf(0f, normalizedBalance)
    val rightVol = 1f - 0.3f * turnAmount - 0.4f * maxOf(0f, -normalizedBalance)

    // 화면이 바뀔 때마다 실제 플레이어에 볼륨 적용
    SideEffect {
        player.setVolume(leftVol, rightVol)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "곧 방향: $effectiveDirection", fontSize = 40.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("깜빡이 자동 연동", fontSize = 20.sp)
            Switch(checked = automatic, onCheckedChange = {
                // 모드 전환 시 이전 수동/자동 방향을 이어받지 않고 가운데부터 시작한다.
                signal = TurnSignal.CENTER
                cue = TurnCue.IDLE
                target = 0f
                direction = "가운데"
                automatic = it
            })
        }
        Text(if (automatic) connectionMessage else "수동 버튼 테스트 모드", fontSize = 18.sp)
        if (automatic) {
            // 테스트 확인용: 예고 판단 결과와 내비 정보
            Text("예고: ${cue.mode.label} · 깜빡이 ${signal.label}", fontSize = 18.sp)
            Text(naviMessage, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("가상 주행 (테스트)", fontSize = 16.sp)
                Switch(checked = simulateDriving, onCheckedChange = { simulateDriving = it })
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "왼쪽 ${(leftVol * 100).roundToInt()}%   |   오른쪽 ${(rightVol * 100).roundToInt()}%",
            fontSize = 28.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(onClick = {
            if (isPlaying) player.pause() else player.start()
            isPlaying = !isPlaying
        }) {
            Text(if (isPlaying) "음악 멈추기" else "음악 재생", fontSize = 24.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row {
            Button(enabled = !automatic, onClick = { direction = "왼쪽"; target = -strength }) {
                Text("좌회전", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(enabled = !automatic, onClick = { direction = "가운데"; target = 0f }) {
                Text("직진", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(enabled = !automatic, onClick = { direction = "오른쪽"; target = strength }) {
                Text("우회전", fontSize = 24.sp)
            }
        }
    }
}