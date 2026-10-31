package com.billmanautovisit.app.core

enum class AutomationFlow(
    val wireValue: String,
    val displayName: String,
    val settlementLabel: String,
    val settlementResourceId: String,
    val settlementSearchText: String = settlementLabel,
    val directFinishAfterSettlement: Boolean = false
) {
    LUNAS_MANDIRI(
        wireValue = "lunas_mandiri",
        displayName = "Alur 1 — Lunas Mandiri",
        settlementLabel = "Lunas Mandiri",
        settlementResourceId = "dev.iconpln.mims:id/radio_lunas_mandiri"
    ),
    LUNAS_OFFLINE(
        wireValue = "lunas_offline",
        displayName = "Alur 2 — Lunas Offline",
        settlementLabel = "Lunas Offline",
        settlementResourceId = "dev.iconpln.mims:id/radio_lunas_petugas"
    ),
    RUMAH_KUNCI_KOSONG(
        wireValue = "rumah_kunci_kosong",
        displayName = "Alur 3 — Rumah Kunci/Kosong",
        settlementLabel = "Rumah Kunci/Kosong",
        settlementResourceId = "dev.iconpln.mims:id/radio_rumah_kosong",
        settlementSearchText = "RUMAH KOSONG",
        directFinishAfterSettlement = true
    ),
    TUL(
        wireValue = "tul",
        displayName = "Alur TUL",
        settlementLabel = "",
        settlementResourceId = "",
        settlementSearchText = ""
    );

    val usesBaTulVisitFlow: Boolean
        get() = this == LUNAS_MANDIRI || this == LUNAS_OFFLINE || this == TUL

    val baTulReasonLabel: String
        get() = when (this) {
            LUNAS_MANDIRI -> "LUNAS MANDIRI"
            LUNAS_OFFLINE, TUL -> "LUNAS OFFLINE"
            RUMAH_KUNCI_KOSONG -> ""
        }

    companion object {
        fun fromWireValue(value: String?): AutomationFlow =
            entries.firstOrNull { it.wireValue == value } ?: LUNAS_MANDIRI
    }
}
