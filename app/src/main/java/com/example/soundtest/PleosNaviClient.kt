package com.example.soundtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private const val TAG = "NaviSDK"

/**
 * TBT 목록에서 "다음 좌/우 회전"을 고른다.
 * 가이드의 알려진 함정: 목록이 가까운 순서로 안 오고, 거리 0짜리(톨게이트 등)가 섞인다 → 정렬·필터.
 * 가장 가까운 안내가 직진·완만한 회전·IC 등이면 예고할 회전이 없는 것으로 본다(문제점 3-②).
 */
internal fun pickNextTurn(tbtInfoList: List<TBTInfo>): NaviTurn? {
    val nearest = tbtInfoList
        .filter { it.distance > 0.0 && it.type !in NON_MANEUVER_TYPES }
        .minByOrNull { it.distance }
        ?: return null
    val direction = when (nearest.type) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_UTURN -> TurnSignal.LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> TurnSignal.RIGHT
        else -> return null
    }
    return NaviTurn(direction, nearest.distance)
}

/** 움직임 안내가 아닌 정보성 항목. 이게 가장 가까워도 다음 회전을 가리면 안 된다. */
private val NON_MANEUVER_TYPES = setOf(
    TurnType.TURN_UNKNOWN,
    TurnType.TURN_TUNNEL,
    TurnType.TURN_TOLL,
    TurnType.TURN_WAY,
    TurnType.TURN_ENTER_SERVICEAREA,
    TurnType.TURN_RESTAREA,
)

/**
 * 공식 Pleos NaviHelper SDK 입력 어댑터.
 * observe()를 수집하는 동안만 내비 서비스에 연결하고, 취소되면 리스너 해제 후 release한다.
 * 내비 연결이 안 되거나 안내 중이 아니면 nextTurn=null → 엔진이 깜빡이만으로 차선변경 예고를 한다.
 */
internal class PleosNaviClient(private val context: Context) {
    fun observe(): Flow<NaviSnapshot> = callbackFlow {
        val navi = NaviHelper(context)
        val handler = Handler(Looper.getMainLooper())
        var stopped = false
        var state = NaviSnapshot.INACTIVE.copy(message = "내비 연결 중")

        // SDK 콜백 스레드가 정해져 있지 않으므로 상태 변경은 메인 스레드에서만 한다.
        fun update(transform: (NaviSnapshot) -> NaviSnapshot) {
            handler.post {
                if (stopped || !isActive) return@post
                state = transform(state)
                trySend(state)
            }
        }

        fun describe(snapshot: NaviSnapshot): String = when {
            !snapshot.active -> "내비 안내 없음 · 깜빡이만 사용"
            snapshot.searching -> "경로 탐색 중"
            snapshot.nextTurn == null -> "가까운 회전 없음"
            else -> "다음 ${snapshot.nextTurn.direction.label} 회전 ${snapshot.nextTurn.distanceMeters.roundToInt()}m"
        }

        fun updateAndDescribe(transform: (NaviSnapshot) -> NaviSnapshot) =
            update { old -> transform(old).let { it.copy(message = describe(it)) } }

        val listener = object : NaviHelperEventListener {
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = updateAndDescribe {
                // TBT가 오면 안내 중이라는 뜻이다(상태 이벤트를 놓쳤을 때 대비).
                it.copy(active = true, nextTurn = pickNextTurn(tbtInfoList))
            }

            override fun onNavigationStatus(status: NavigationStatus) {
                Log.i(TAG, "내비 상태: $status")
                updateAndDescribe {
                    val routing = status == NavigationStatus.ROUTE_DRIVE
                    it.copy(active = routing, nextTurn = if (routing) it.nextTurn else null)
                }
            }

            override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                Log.i(TAG, "경로 탐색 시작")
                updateAndDescribe { it.copy(searching = true, nextTurn = null) }
            }

            override fun onRoutingCompleted() {
                Log.i(TAG, "경로 탐색 완료")
                updateAndDescribe { it.copy(searching = false) }
            }

            override fun onReRouteEnded() {
                Log.i(TAG, "재탐색 완료")
                updateAndDescribe { it.copy(searching = false) }
            }

            override fun onRouteCancelled() {
                Log.i(TAG, "경로 취소")
                updateAndDescribe { it.copy(active = false, searching = false, nextTurn = null) }
            }

            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                Log.i(TAG, "목적지 도착")
                updateAndDescribe { it.copy(active = false, searching = false, nextTurn = null) }
            }
        }

        trySend(state)
        try {
            navi.addListener(listener)
            navi.initialize(
                onConnected = {
                    handler.post {
                        if (stopped || !isActive) return@post
                        Log.i(TAG, "NaviHelper 연결됨")
                        updateAndDescribe { it }
                        // 이미 안내 중이면 현재 TBT를 한 번 받아온다. 결과는 onTBTInfo로 온다.
                        try {
                            navi.getTBTInfo()
                        } catch (error: Exception) {
                            Log.w(TAG, "TBT 조회 실패 (이벤트로만 받음)", error)
                        }
                    }
                },
                onFailed = { error ->
                    Log.e(TAG, "NaviHelper 연결 실패", error)
                    update { it.copy(active = false, nextTurn = null, message = "내비 연결 실패 · 깜빡이만 사용") }
                },
            )
        } catch (error: Exception) {
            Log.e(TAG, "NaviHelper 초기화 오류", error)
            update { it.copy(active = false, nextTurn = null, message = "내비 연결 실패 · 깜빡이만 사용") }
        }

        awaitClose {
            stopped = true
            try {
                navi.removeListener(listener)
            } catch (error: Exception) {
                Log.w(TAG, "내비 리스너 해제 실패", error)
            } finally {
                try { navi.release() } catch (error: Exception) {
                    Log.w(TAG, "내비 연결 해제 실패", error)
                }
                Log.i(TAG, "내비 구독 종료")
            }
        }
    }.flowOn(Dispatchers.Main.immediate)
}
