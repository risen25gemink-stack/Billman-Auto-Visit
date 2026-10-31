package com.billmanautovisit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaTulFlowRoutingTest {
    @Test fun offlineAndMandiriUseBaTulFlow() {
        assertTrue(AutomationFlow.LUNAS_OFFLINE.usesBaTulVisitFlow)
        assertTrue(AutomationFlow.LUNAS_MANDIRI.usesBaTulVisitFlow)
    }

    @Test fun offlineAndTulSelectOfflineReason() {
        assertEquals("LUNAS OFFLINE", AutomationFlow.LUNAS_OFFLINE.baTulReasonLabel)
        assertEquals("LUNAS OFFLINE", AutomationFlow.TUL.baTulReasonLabel)
    }

    @Test fun mandiriSelectsMandiriReason() {
        assertEquals("LUNAS MANDIRI", AutomationFlow.LUNAS_MANDIRI.baTulReasonLabel)
    }

    @Test fun rumahKosongDoesNotUseBaTulFlow() {
        assertEquals(false, AutomationFlow.RUMAH_KUNCI_KOSONG.usesBaTulVisitFlow)
    }
}
