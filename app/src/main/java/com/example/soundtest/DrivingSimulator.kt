package com.example.soundtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 가상 주행 속도: 초당 10m = 시속 36km */
internal const val SIM_SPEED_MPS = 10.0
private const val SIM_TICK_MS = 200L

/**
 * [테스트용] 가상 주행.
 * 에뮬레이터 내장 내비는 에뮬레이터 GPS를 쓰지 않아서(로그 sat(0), 위치 고정) 차가 움직이지 않는다.
 * 그래서 내비에서 받은 "진짜 다음 회전"을 시작점으로 잡고, 남은 거리를 앱 안에서 초당 10m씩 줄인다.
 * 깜빡이는 버추얼 콘솔의 진짜 신호를 그대로 쓰므로, 예고 판단 엔진은 실제와 똑같이 동작한다.
 * 회전 지점(0m)에 닿으면 "회전 통과"로 바꾼다. 다시 하려면 스위치를 껐다 켠다.
 * 나중에 시연 모드(11/9~11)의 바탕으로 쓸 수 있다.
 */
internal fun Flow<NaviSnapshot>.withSimulatedDriving(speedMps: Double = SIM_SPEED_MPS): Flow<NaviSnapshot> =
    channelFlow {
        var latest = NaviSnapshot.INACTIVE
        var remaining: Double? = null
        var direction: TurnSignal? = null

        // 실제 내비 정보는 계속 받되, 출발점(첫 회전)은 한 번만 잡는다.
        launch {
            this@withSimulatedDriving.collect { snapshot ->
                latest = snapshot
                val turn = snapshot.nextTurn
                if (remaining == null && snapshot.active && turn != null) {
                    remaining = turn.distanceMeters
                    direction = turn.direction
                }
            }
        }

        while (isActive) {
            val left = remaining
            val dir = direction
            val out = when {
                left == null || dir == null ->
                    latest.copy(message = "[가상 주행] 내비 회전 정보 기다리는 중\n${latest.message}")
                left > 0.0 -> latest.copy(
                    active = true,
                    searching = false,
                    nextTurn = NaviTurn(dir, left),
                    message = "[가상 주행 36km/h] 다음 ${dir.label} 회전 ${left.roundToInt()}m",
                )
                else -> latest.copy(
                    active = true,
                    searching = false,
                    nextTurn = null,
                    message = "[가상 주행] ${dir.label} 회전 통과 · 깜빡이를 꺼주세요",
                )
            }
            send(out)
            delay(SIM_TICK_MS)
            if (left != null && left > 0.0) {
                remaining = (left - speedMps * SIM_TICK_MS / 1000.0).coerceAtLeast(0.0)
            }
        }
    }.flowOn(Dispatchers.Main.immediate)
