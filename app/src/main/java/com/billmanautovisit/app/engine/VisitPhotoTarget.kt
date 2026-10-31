package com.billmanautovisit.app.engine

internal data class VisitPhotoTarget(
    val isBaTul: Boolean,
    val cameraResourceIds: List<String>,
    val label: String
)

internal fun visitPhotoTargetFor(isVi01: Boolean): VisitPhotoTarget =
    if (isVi01) {
        VisitPhotoTarget(true, listOf(
            "dev.iconpln.mims:id/btn_poto_penyampaian_ba",
            "dev.iconpln.mims:id/iv_poto_penyampaian_ba",
            "dev.iconpln.mims:id/ambil_foto_penyampaian_ba_tul",
            "dev.iconpln.mims:id/btn_ambil_foto_penyampaian_ba_tul",
            "dev.iconpln.mims:id/foto_form_penyerahan_ba_tul_vi_01"
        ), "Foto Penyampaian BA TUL VI-01")
    } else {
        VisitPhotoTarget(false, listOf(
            "dev.iconpln.mims:id/btn_photo_penyerahan_invoice",
            "dev.iconpln.mims:id/iv_bukti_form_penyerahan_invoice"
        ), "Foto form penyerahan Invoice INTAG")
    }
