package com.example.soundtest

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnSignalTest {
    @Test fun sdkLeftAndRightMapToAudioDirections() {
        assertEquals(TurnSignal.LEFT, decodeSdkTurnSignal(TurnSignalValue.LEFT))
        assertEquals(TurnSignal.RIGHT, decodeSdkTurnSignal(TurnSignalValue.RIGHT))
    }
    @Test fun offReturnsToCenter() {
        assertEquals(TurnSignal.CENTER, decodeSdkTurnSignal(TurnSignalValue.OFF))
    }
    @Test fun unavailableValueDoesNotKeepOldDirection() {
        assertEquals(TurnSignal.CENTER, decodeSdkTurnSignal(null))
    }
}
