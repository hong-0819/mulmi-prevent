package com.example.soundtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "MulmiService"

/** 화면에서 서비스로 보내는 설정 (화면이 바꾸고 서비스가 따른다) */
internal data class MulmiSettings(
    val playing: Boolean = false,
    val automatic: Boolean = true,
    val simulateDriving: Boolean = false,
    val manualDirection: TurnSignal = TurnSignal.CENTER,
)

/** 서비스가 화면에 알려주는 현재 상태 (서비스가 바꾸고 화면은 보여주기만 한다) */
internal data class MulmiStatus(
    val serviceRunning: Boolean = false,
    val signal: TurnSignal = TurnSignal.CENTER,
    val cue: TurnCue = TurnCue.IDLE,
    val connectionMessage: String = "서비스 시작 전",
    val naviMessage: String = "내비 대기",
    val leftVolume: Float = 1f,
    val rightVolume: Float = 1f,
)

/** 화면과 서비스가 같은 앱 프로세스 안에서 주고받는 창구 */
internal object MulmiController {
    val settings = MutableStateFlow(MulmiSettings())
    val status = MutableStateFlow(MulmiStatus())

    fun updateSettings(transform: (MulmiSettings) -> MulmiSettings) = settings.update(transform)
}

/**
 * 멀미 완화 포그라운드 서비스.
 * 내비 화면을 띄워 우리 앱이 안 보여도 계속 동작해야 하므로, 음악·깜빡이·내비·예고 판단·음량 조절을
 * 전부 여기서 한다. (이전에는 화면 안에 있어서 화면이 가려지면 구독이 끊기고 음량도 안 바뀌었다)
 * 음량 공식과 0.8초 부드러운 전환은 이전 화면 코드와 같다. 세기 7:3.
 */
class MulmiPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null

    /** -1 = 완전 왼쪽, 0 = 가운데, +1 = 완전 오른쪽 */
    private var balance = 0f
    private var rampJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        player = MediaPlayer.create(this, R.raw.calm_music)?.apply { isLooping = true }
        applyBalance(0f)
        MulmiController.status.update { it.copy(serviceRunning = true) }
        Log.i(TAG, "멀미 완화 서비스 시작")

        // 음악 재생 / 멈춤
        scope.launch {
            MulmiController.settings
                .map { it.playing }
                .distinctUntilChanged()
                .collect { playing ->
                    val mp = player ?: return@collect
                    if (playing) {
                        if (!mp.isPlaying) mp.start()
                    } else if (mp.isPlaying) {
                        mp.pause()
                    }
                }
        }

        // 방향 결정: 자동(깜빡이+내비) 또는 수동 버튼
        scope.launch {
            MulmiController.settings
                .map { Triple(it.automatic, it.simulateDriving, it.manualDirection) }
                .distinctUntilChanged()
                .collectLatest { (automatic, simulate, manualDirection) ->
                    if (automatic) {
                        runAutomatic(simulate)
                    } else {
                        MulmiController.status.update {
                            it.copy(
                                signal = TurnSignal.CENTER,
                                cue = TurnCue.IDLE,
                                connectionMessage = "수동 버튼 테스트 모드",
                                naviMessage = "내비 대기",
                            )
                        }
                        rampTo(manualDirection)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        Log.i(TAG, "멀미 완화 서비스 종료")
        scope.cancel()
        player?.release()
        player = null
        MulmiController.status.value = MulmiStatus()
        MulmiController.settings.update { it.copy(playing = false) }
        super.onDestroy()
    }

    /** 깜빡이·내비를 구독해 예고 판단 결과대로 음량을 바꾼다. 설정이 바뀌면 collectLatest가 취소하고 다시 시작한다. */
    private suspend fun runAutomatic(simulate: Boolean) {
        try {
            MulmiController.status.update { it.copy(connectionMessage = "Vehicle SDK 연결 중") }
            val signals = PleosTurnSignalClient(applicationContext).observe()
                .onEach { snapshot ->
                    MulmiController.status.update {
                        it.copy(signal = snapshot.signal, connectionMessage = snapshot.message)
                    }
                }
                .map { it.signal }
            val realNavi = PleosNaviClient(applicationContext).observe()
            val navi = (if (simulate) realNavi.withSimulatedDriving() else realNavi)
                .onEach { snapshot -> MulmiController.status.update { it.copy(naviMessage = snapshot.message) } }
            val speeds = flowOf(if (simulate) SIM_SPEED_MPS else null)

            turnCues(signals, navi, speeds).collect { cue ->
                MulmiController.status.update { it.copy(cue = cue) }
                rampTo(cue.direction)
            }
        } finally {
            MulmiController.status.update {
                it.copy(signal = TurnSignal.CENTER, cue = TurnCue.IDLE, naviMessage = "내비 대기")
            }
            rampTo(TurnSignal.CENTER)
        }
    }

    /** 0.8초 동안 부드럽게 목표 방향으로 음량을 옮긴다 (이전 화면의 tween(800)과 같은 역할). */
    private fun rampTo(direction: TurnSignal) {
        val target = when (direction) {
            TurnSignal.LEFT -> -1f
            TurnSignal.RIGHT -> 1f
            TurnSignal.CENTER -> 0f
        }
        rampJob?.cancel()
        rampJob = scope.launch {
            val start = balance
            val steps = (RAMP_DURATION_MS / RAMP_FRAME_MS).toInt()
            for (step in 1..steps) {
                applyBalance(start + (target - start) * step / steps)
                delay(RAMP_FRAME_MS)
            }
            applyBalance(target)
        }
    }

    /** 가운데: 100%/100%, 왼쪽: 70%/30%, 오른쪽: 30%/70% (팀 확정 세기 7:3) */
    private fun applyBalance(newBalance: Float) {
        balance = newBalance.coerceIn(-1f, 1f)
        val turnAmount = abs(balance)
        val leftVol = 1f - 0.3f * turnAmount - 0.4f * maxOf(0f, balance)
        val rightVol = 1f - 0.3f * turnAmount - 0.4f * maxOf(0f, -balance)
        player?.setVolume(leftVol, rightVol)
        MulmiController.status.update { it.copy(leftVolume = leftVol, rightVolume = rightVol) }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "멀미 완화 모드", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("멀미 완화 모드")
            .setContentText("깜빡이·내비에 맞춰 음악 방향을 조절하는 중")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "mulmi_mode"
        const val NOTIFICATION_ID = 1
        const val RAMP_DURATION_MS = 800L
        const val RAMP_FRAME_MS = 16L
    }
}
