package com.example.soundtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** 앱의 음향 계산에 사용할 방향. SDK 타입을 화면/음량 코드와 분리한다. */
internal enum class TurnSignal(val label: String) {
    LEFT("왼쪽"), RIGHT("오른쪽"), CENTER("가운데")
}

internal data class TurnSignalSnapshot(val signal: TurnSignal, val message: String)

/** 값이 없을 때 이전 방향을 유지하지 않고 가운데로 복귀한다. */
internal fun decodeSdkTurnSignal(state: TurnSignalValue?): TurnSignal = when (state) {
    TurnSignalValue.LEFT -> TurnSignal.LEFT
    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    TurnSignalValue.OFF, null -> TurnSignal.CENTER
}

/**
 * 공식 Pleos Vehicle SDK 입력 어댑터. 웹 주소나 콘솔 HTTP 서버를 사용하지 않는다.
 * 콘솔은 에뮬레이터의 차량 상태를 바꾸는 테스트 도구이고, 앱은 SDK로 그 상태를 받는다.
 * observe()를 수집하는 동안만 연결하며, 취소되면 같은 리스너를 해제한 뒤 release한다.
 * 실제 차량에서는 해당 기능과 Vehicle Service 지원 여부/권한을 별도로 검증해야 한다.
 */
internal class PleosTurnSignalClient(private val context: Context) {
    fun observe(): Flow<TurnSignalSnapshot> = callbackFlow {
        val vehicle = Vehicle(context)
        val handler = Handler(Looper.getMainLooper())
        var stopped = false
        var registrationAttempted = false
        var eventRevision = 0L

        // SDK 콜백 스레드는 일정하지 않다. 상태와 등록/해제 처리를 메인 스레드로 모은다.
        // 연결 해제 후 늦게 도착한 콜백은 stopped/isActive로 무시한다.
        fun onMain(action: () -> Unit) {
            handler.post { if (!stopped && isActive) action() }
        }

        fun fail(error: Exception) {
            Log.e("VehicleSDK", "깜빡이 SDK 오류", error)
            trySend(TurnSignalSnapshot(TurnSignal.CENTER, "Vehicle SDK 연결 확인 필요 · 가운데 유지"))
        }

        fun publish(state: TurnSignalValue?) {
            val direction = decodeSdkTurnSignal(state)
            val message = when (state) {
                null -> "Vehicle SDK 상태 확인 불가 · 가운데 유지"
                TurnSignalValue.OFF -> "Vehicle SDK · 깜빡이 꺼짐"
                else -> "Vehicle SDK · ${direction.label} 깜빡이"
            }
            Log.i("VehicleSDK", "SDK 음량 입력: $state -> ${direction.label}")
            trySend(TurnSignalSnapshot(direction, message))
        }

        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = onMain {
                eventRevision++
                publish(state)
            }
            override fun onFailed(exception: Exception) = onMain {
                eventRevision++
                fail(exception)
            }
        }

        try {
            vehicle.initialize()
            val api = vehicle.getTurnSignal()
            api.checkTurnSignalStateCapabilities(
                onComplete = { capability -> onMain {
                    if (capability.accessType != AccessType.READ &&
                        capability.accessType != AccessType.READ_WRITE) {
                        fail(IllegalStateException("Turn signal read is unsupported"))
                    } else {
                        try {
                            // 먼저 구독해 초기 조회 중에 발생한 변경도 놓치지 않는다.
                            registrationAttempted = true
                            api.registerTurnSignalState(listener)
                            Log.i("VehicleSDK", "깜빡이 SDK 구독 등록")
                            val readRevision = eventRevision
                            api.getCurrentTurnSignalState(
                                onComplete = { state -> onMain {
                                    // 조회 결과가 늦게 와도 그동안 받은 최신 변경을 덮어쓰지 않는다.
                                    if (eventRevision == readRevision) publish(state)
                                } },
                                onFailed = { error -> onMain {
                                    if (eventRevision == readRevision) fail(error)
                                } }
                            )
                        } catch (error: Exception) { fail(error) }
                    }
                } },
                onFailed = { error -> onMain { fail(error) } }
            )
        } catch (error: Exception) { fail(error) }

        awaitClose {
            stopped = true
            try {
                if (registrationAttempted) vehicle.getTurnSignal().unregisterTurnSignalState(listener)
            } catch (error: Exception) {
                Log.w("VehicleSDK", "깜빡이 구독 해제 실패", error)
            } finally {
                try { vehicle.release() } catch (error: Exception) {
                    Log.w("VehicleSDK", "차량 연결 해제 실패", error)
                }
                Log.i("VehicleSDK", "깜빡이 SDK 구독 종료")
            }
        }
    }.flowOn(Dispatchers.Main.immediate)
}
