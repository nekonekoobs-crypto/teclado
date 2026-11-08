package com.example.stickerboard

import android.graphics.Color

data class KbTheme(
    val name: String,
    val bg: Int,
    val key: Int,
    val keyText: Int,
    val special: Int,
    val accent: Int
)

object Themes {
    private fun c(hex: String) = Color.parseColor(hex)

    val all = listOf(
        KbTheme("Oscuro", c("#1E1E24"), c("#33333D"), c("#FFFFFF"), c("#26262E"), c("#7C5CFF")),
        KbTheme("Claro", c("#E8EAF0"), c("#FFFFFF"), c("#1B1B1F"), c("#C9CCD6"), c("#3D6DFF")),
        KbTheme("Océano", c("#0B2A3C"), c("#14425C"), c("#E6F6FF"), c("#0F3650"), c("#29C5F6")),
        KbTheme("Bosque", c("#16261C"), c("#24402F"), c("#E8F5EC"), c("#1C3325"), c("#4ADE80")),
        KbTheme("Rosa", c("#FFE3EC"), c("#FFFFFF"), c("#4A1530"), c("#FFC2D6"), c("#F0457E")),
        KbTheme("Atardecer", c("#2B1633"), c("#46244F"), c("#FFE9D6"), c("#381C41"), c("#FF8A3D"))
    )
}
