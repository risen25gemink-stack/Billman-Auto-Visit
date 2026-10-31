package com.billmanautovisit.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitPhotoTargetTest {
    @Test fun vi01SelectsBaTulPhotoInsteadOfInvoicePhoto() {
        val target = visitPhotoTargetFor(isVi01 = true)
        assertTrue(target.isBaTul)
        assertEquals("dev.iconpln.mims:id/btn_poto_penyampaian_ba", target.cameraResourceIds.first())
        assertEquals("Foto Penyampaian BA TUL VI-01", target.label)
        assertFalse(target.cameraResourceIds.contains("dev.iconpln.mims:id/btn_photo_penyerahan_invoice"))
    }
    @Test fun nonVi01KeepsInvoicePhotoTarget() {
        val target = visitPhotoTargetFor(isVi01 = false)
        assertFalse(target.isBaTul)
        assertEquals("dev.iconpln.mims:id/btn_photo_penyerahan_invoice", target.cameraResourceIds.first())
        assertEquals("Foto form penyerahan Invoice INTAG", target.label)
    }
}
