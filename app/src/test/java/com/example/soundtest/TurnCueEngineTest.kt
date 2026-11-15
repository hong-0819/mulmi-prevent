package com.example.soundtest

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 코딩 가이드 문제점 4 표의 상황들을 그대로 확인한다. */
class TurnCueEngineTest {

    private fun naviWithTurn(direction: TurnSignal, meters: Double) =
        NaviSnapshot(active = true, searching = false, nextTurn = NaviTurn(direction, meters), message = "")

    @Test
    fun 깜빡이만_켜지면_2초간_차선변경_후_가운데() {
        val engine = TurnCueEngine()
        engine.onSignal(TurnSignal.LEFT, nowMs = 0)
        assertEquals(TurnCue(TurnSignal.LEFT, CueMode.LANE_CHANGE), engine.evaluate(0))
        assertEquals(TurnCue(TurnSignal.LEFT, CueMode.LANE_CHANGE), engine.evaluate(1_999))
        assertEquals(2_000L, engine.nextWakeUpAt(1_999))
        // 깜빡이가 계속 켜져 있어도 2초 뒤엔 가운데
        assertEquals(TurnCue.IDLE, engine.evaluate(2_000))
    }

    @Test
    fun 내비_회전과_같은_방향_깜빡이면_회전_예고_유지() {
        val engine = TurnCueEngine()
        engine.onSpeed(36.0 / 3.6)
        engine.onNavi(naviWithTurn(TurnSignal.RIGHT, 25.0))
        engine.onSignal(TurnSignal.RIGHT, nowMs = 0)
        assertEquals(TurnCue(TurnSignal.RIGHT, CueMode.TURN), engine.evaluate(0))
        // 2초가 지나도 회전 중이면 계속 유지
        engine.onNavi(naviWithTurn(TurnSignal.RIGHT, 5.0))
        assertEquals(TurnCue(TurnSignal.RIGHT, CueMode.TURN), engine.evaluate(10_000))
        // 회전 끝나고 깜빡이 꺼지면 가운데
        engine.onSignal(TurnSignal.CENTER, nowMs = 11_000)
        assertEquals(TurnCue.IDLE, engine.evaluate(11_000))
    }

    @Test
    fun 회전이_멀면_차선변경_후_가까워지면_회전_예고() {
        val engine = TurnCueEngine()
        engine.onSpeed(36.0 / 3.6)
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 200.0))
        engine.onSignal(TurnSignal.LEFT, nowMs = 0)
        assertEquals(CueMode.LANE_CHANGE, engine.evaluate(0).mode)
        assertEquals(TurnCue.IDLE, engine.evaluate(3_000))
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 28.0))
        assertEquals(TurnCue(TurnSignal.LEFT, CueMode.TURN), engine.evaluate(8_000))
    }

    @Test
    fun 속도가_10이상이고_거리가_30이내일_때만_회전_예고() {
        val engine = TurnCueEngine()
        engine.onSignal(TurnSignal.RIGHT, nowMs = 0)
        engine.onNavi(naviWithTurn(TurnSignal.RIGHT, 30.1))
        engine.onSpeed(10.0 / 3.6)
        assertEquals(TurnCue.IDLE, engine.evaluate(2_500))
        engine.onNavi(naviWithTurn(TurnSignal.RIGHT, 30.0))
        engine.onSpeed(null)
        assertEquals(TurnCue.IDLE, engine.evaluate(2_600))
        engine.onSpeed(9.9 / 3.6)
        assertEquals(TurnCue.IDLE, engine.evaluate(2_700))
        engine.onSpeed(10.0 / 3.6)
        assertEquals(TurnCue(TurnSignal.RIGHT, CueMode.TURN), engine.evaluate(3_000))
    }

    @Test
    fun 내비_회전인데_깜빡이_없으면_가운데() {
        val engine = TurnCueEngine()
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 10.0))
        assertEquals(TurnCue.IDLE, engine.evaluate(0))
    }

    @Test
    fun 내비_회전과_반대_깜빡이면_깜빡이_방향_차선변경() {
        val engine = TurnCueEngine()
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 10.0))
        engine.onSignal(TurnSignal.RIGHT, nowMs = 0)
        assertEquals(TurnCue(TurnSignal.RIGHT, CueMode.LANE_CHANGE), engine.evaluate(0))
    }

    @Test
    fun 경로_재탐색_중이면_가운데로_멈춤() {
        val engine = TurnCueEngine()
        engine.onSpeed(36.0 / 3.6)
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 10.0))
        engine.onSignal(TurnSignal.LEFT, nowMs = 0)
        assertEquals(CueMode.TURN, engine.evaluate(0).mode)
        engine.onNavi(NaviSnapshot(active = true, searching = true, nextTurn = null, message = ""))
        assertEquals(TurnCue(TurnSignal.CENTER, CueMode.PAUSED), engine.evaluate(500))
    }

    @Test
    fun 다음_안내로_넘어가면_회전_예고_해제() {
        val engine = TurnCueEngine()
        engine.onSpeed(36.0 / 3.6)
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 10.0))
        engine.onSignal(TurnSignal.LEFT, nowMs = 0)
        assertEquals(CueMode.TURN, engine.evaluate(0).mode)
        // 회전을 지나 다음 안내(멀리 있는 왼쪽 회전)로 바뀜, 깜빡이는 아직 켜짐
        engine.onNavi(naviWithTurn(TurnSignal.LEFT, 400.0))
        assertEquals(TurnCue.IDLE, engine.evaluate(5_000))
    }

    @Test
    fun TBT_목록은_경로_순서이고_첫_안내가_다음_회전() {
        // 에뮬레이터에서 실제로 받은 목록 앞부분 (80m 후 좌회전, 그다음 380m 후 우회전)
        val list = listOf(
            TBTInfo(TurnType.TURN_LEFT, 80.0, ""),
            TBTInfo(TurnType.TURN_RIGHT, 380.0, ""),
            TBTInfo(TurnType.TURN_ARRIVE, 20.0, ""),
        )
        assertEquals(NaviTurn(TurnSignal.LEFT, 80.0), pickNextTurn(list))
    }

    @Test
    fun 정보성_항목은_거리만_더하고_건너뜀() {
        val list = listOf(
            TBTInfo(TurnType.TURN_TOLL, 50.0, ""),
            TBTInfo(TurnType.TURN_RIGHT, 30.0, ""),
        )
        assertEquals(NaviTurn(TurnSignal.RIGHT, 80.0), pickNextTurn(list))
    }

    @Test
    fun 다음_안내가_직진이나_완만한_회전이면_예고_없음() {
        assertNull(pickNextTurn(listOf(TBTInfo(TurnType.TURN_STRAIGHT, 80.0, ""), TBTInfo(TurnType.TURN_LEFT, 500.0, ""))))
        assertNull(pickNextTurn(listOf(TBTInfo(TurnType.TURN_KEEP_L, 80.0, ""))))
        assertNull(pickNextTurn(listOf(TBTInfo(TurnType.TURN_ARRIVE, 20.0, ""))))
        assertNull(pickNextTurn(emptyList()))
    }
}
