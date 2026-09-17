package com.billmanautovisit.app.core

enum class AutomationFlow(
    val wireValue: String,
    val displayName: String,
    val settlementLabel: String,
    val settlementResourceId: String
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
    );

    companion object {
        fun fromWireValue(value: String?): AutomationFlow =
            entries.firstOrNull { it.wireValue == value } ?: LUNAS_MANDIRI
    }
}
