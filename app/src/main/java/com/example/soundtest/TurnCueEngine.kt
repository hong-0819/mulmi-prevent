package com.example.soundtest

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 예고 판단(A 파트). 깜빡이 + 내비 회전 정보를 받아 음악 쪽에 넘길 방향(LEFT/RIGHT/CENTER)을 정한다.
 * 코딩 가이드 문제점 2·3·4 기준:
 *  - 내비 회전 + 같은 방향 깜빡이 + 회전 직전 → TURN: 깜빡이가 꺼지거나 다음 안내로 넘어갈 때까지 유지
 *  - 가까운 회전 없이 깜빡이만 켜짐(내비 꺼짐 포함) → LANE_CHANGE: 2초만 쏠리고 가운데 복귀 (깜빡이가 계속 켜져 있어도)
 *  - 내비 회전 + 깜빡이 없음 → 가운데
 *  - 경로 탐색/재탐색 중 → PAUSED: 가운데, 새 경로 올 때까지 멈춤
 * 회전과 차선변경의 세기는 같고(음악 쪽 담당) 쏠려 있는 시간만 다르다.
 */
internal enum class CueMode(val label: String) {
    IDLE("대기"), TURN("회전 예고"), LANE_CHANGE("차선변경"), PAUSED("경로 탐색 중")
}

internal data class TurnCue(val direction: TurnSignal, val mode: CueMode) {
    companion object {
        val IDLE = TurnCue(TurnSignal.CENTER, CueMode.IDLE)
    }
}

/** 내비가 알려준 가장 가까운 좌/우 회전. */
internal data class NaviTurn(val direction: TurnSignal, val distanceMeters: Double)

/** 예고 판단에 필요한 내비 상태만 추린 것. SDK 타입과 분리해 테스트하기 쉽게 한다. */
internal data class NaviSnapshot(
    val active: Boolean,
    val searching: Boolean,
    val nextTurn: NaviTurn?,
    val message: String,
) {
    companion object {
        val INACTIVE = NaviSnapshot(active = false, searching = false, nextTurn = null, message = "내비 안내 없음")
    }
}

internal class TurnCueEngine(
    /** 차선변경 "툭" 유지 시간 */
    private val laneChangeHoldMs: Long = 2_000L,
    /** 회전 몇 초 전부터 쏠릴지 (속도를 알 때) */
    private val turnLeadSeconds: Double = 2.0,
    /** 속도를 모르거나 시속 5km 미만일 때 쓰는 회전 지점 거리 기준 */
    private val nearTurnMeters: Double = 30.0,
) {
    private var signal = TurnSignal.CENTER
    private var navi = NaviSnapshot.INACTIVE
    private var speedMps: Double? = null

    private var laneChangeDirection = TurnSignal.CENTER
    private var laneChangeUntilMs = 0L

    private var latchedTurn = TurnSignal.CENTER
    private var latchedDistance = Double.MAX_VALUE

    fun onSignal(newSignal: TurnSignal, nowMs: Long) {
        if (newSignal == signal) return
        signal = newSignal
        if (newSignal == TurnSignal.CENTER) {
            laneChangeUntilMs = 0L
        } else {
            // 깜빡이가 새로 켜진(또는 방향이 바뀐) 순간부터 2초 "툭"
            laneChangeDirection = newSignal
            laneChangeUntilMs = nowMs + laneChangeHoldMs
        }
    }

    fun onNavi(snapshot: NaviSnapshot) {
        navi = snapshot
    }

    /** 차 속도(m/s). 아직 속도 입력이 연결되지 않았으면 null → 거리 기준(30m)을 쓴다. */
    fun onSpeed(metersPerSecond: Double?) {
        speedMps = metersPerSecond
    }

    fun evaluate(nowMs: Long): TurnCue {
        if (signal == TurnSignal.CENTER) {
            releaseTurn()
            return TurnCue.IDLE
        }
        if (navi.active && navi.searching) {
            // 깜빡이 켜두고 경로가 바뀌는 중 → 틀린 예고를 막기 위해 바로 가운데
            releaseTurn()
            laneChangeUntilMs = 0L
            return TurnCue(TurnSignal.CENTER, CueMode.PAUSED)
        }

        val turn = navi.nextTurn?.takeIf { navi.active }

        if (latchedTurn != TurnSignal.CENTER) {
            val passed = turn != null &&
                (turn.direction != latchedTurn || turn.distanceMeters > latchedDistance + PASSED_MARGIN_METERS)
            if (signal != latchedTurn || passed) {
                releaseTurn()
            } else if (turn != null) {
                latchedDistance = minOf(latchedDistance, turn.distanceMeters)
            }
        }

        if (latchedTurn == TurnSignal.CENTER && turn != null &&
            turn.direction == signal && isTurnImminent(turn.distanceMeters)
        ) {
            latchedTurn = signal
            latchedDistance = turn.distanceMeters
        }

        if (latchedTurn != TurnSignal.CENTER) return TurnCue(latchedTurn, CueMode.TURN)

        if (laneChangeDirection == signal && nowMs < laneChangeUntilMs) {
            return TurnCue(signal, CueMode.LANE_CHANGE)
        }
        return TurnCue.IDLE
    }

    /** 차선변경 2초가 끝나는 시각. 그때 다시 evaluate()를 불러야 가운데로 돌아간다. */
    fun nextWakeUpAt(nowMs: Long): Long? = laneChangeUntilMs.takeIf { it > nowMs }

    private fun isTurnImminent(distanceMeters: Double): Boolean {
        val speed = speedMps
        return if (speed != null && speed >= SLOW_SPEED_MPS) {
            distanceMeters / speed <= turnLeadSeconds
        } else {
            // 속도를 모르거나 정차/저속이면 회전 지점 30m 이내일 때만 (가이드 문제점 5)
            distanceMeters <= nearTurnMeters
        }
    }

    private fun releaseTurn() {
        latchedTurn = TurnSignal.CENTER
        latchedDistance = Double.MAX_VALUE
    }

    private companion object {
        const val SLOW_SPEED_MPS = 5.0 / 3.6
        /** 남은 거리가 이만큼 늘어나면 그 회전을 지나 다음 안내로 넘어간 것으로 본다 */
        const val PASSED_MARGIN_METERS = 50.0
    }
}

/**
 * 깜빡이·내비 흐름을 합쳐 TurnCue 흐름으로 만든다.
 * 모든 처리는 메인 스레드 한 곳에서 일어나므로 엔진에 동시 접근이 없다.
 */
internal fun turnCues(
    signals: Flow<TurnSignal>,
    navi: Flow<NaviSnapshot>,
    /** 차 속도(m/s). 아직 실제 속도 입력이 없으면 null 하나만 보내면 된다 → 30m 기준 사용 */
    speeds: Flow<Double?> = flowOf(null),
    clock: () -> Long = { SystemClock.elapsedRealtime() },
): Flow<TurnCue> = channelFlow {
    val engine = TurnCueEngine()
    var wakeJob: Job? = null

    fun publish() {
        val now = clock()
        trySend(engine.evaluate(now))
        wakeJob?.cancel()
        wakeJob = engine.nextWakeUpAt(now)?.let { wakeAt ->
            launch {
                delay(wakeAt - now)
                publish()
            }
        }
    }

    send(TurnCue.IDLE)
    launch { signals.collect { engine.onSignal(it, clock()); publish() } }
    launch { navi.collect { engine.onNavi(it); publish() } }
    launch { speeds.collect { engine.onSpeed(it); publish() } }
}.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)
