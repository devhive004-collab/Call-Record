package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Single source of truth: plain vals per theme. Previous @Composable getters
// called isSystemInDarkTheme() internally, creating a second source of truth
// that disagreed with Theme.kt's darkTheme param (mixed light/dark scheme).
// Screens should prefer MaterialTheme.colorScheme; these vals back Theme.kt
// and legacy direct usages (dark-first app).

// Dark palette
val HighDensityBgDark = Color(0xFF141419)
val HighDensityTextDark = Color(0xFFE2E2E6)
val HighDensityPrimaryDark = Color(0xFF80BFFF)
val HighDensityOnPrimaryDark = Color(0xFF003258)
val HighDensityAccentContainerDark = Color(0xFF00497D)
val HighDensityOnAccentContainerDark = Color(0xFFD1E4FF)
val HighDensityBorderDark = Color(0xFF44474E)
val HighDensitySubTextDark = Color(0xFFAAAAB4)
val HighDensityCardBgDark = Color(0xFF1E1E22)
val HighDensitySurfaceDark = Color(0xFF1E1E22)

// Light palette
val HighDensityBgLight = Color(0xFFF7F9FF)
val HighDensityTextLight = Color(0xFF1B1B1F)
val HighDensityPrimaryLight = Color(0xFF0061A4)
val HighDensityOnPrimaryLight = Color.White
val HighDensityAccentContainerLight = Color(0xFFD1E4FF)
val HighDensityOnAccentContainerLight = Color(0xFF001D36)
val HighDensityBorderLight = Color(0xFFDDE2F0)
val HighDensitySubTextLight = Color(0xFF44474E)
val HighDensityCardBgLight = Color.White
val HighDensitySurfaceLight = Color.White

// Legacy aliases (dark-first) — keep compiling for existing screens.
// New code should use MaterialTheme.colorScheme instead.
val HighDensityBg = HighDensityBgDark
val HighDensityText = HighDensityTextDark
val HighDensityPrimary = HighDensityPrimaryDark
val HighDensityOnPrimary = HighDensityOnPrimaryDark
val HighDensityAccentContainer = HighDensityAccentContainerDark
val HighDensityOnAccentContainer = HighDensityOnAccentContainerDark
val HighDensityBorder = HighDensityBorderDark
val HighDensitySubText = HighDensitySubTextDark
val HighDensityCardBg = HighDensityCardBgDark

val SoftGray = Color(0xFF8E8E93)
val WhiteIce = Color(0xFFF5F5F7)

val InboundCallColor = Color(0xFF29B6F6)
val OutboundCallColor = Color(0xFF66BB6A)
val MicRecordingColor = Color(0xFFFF7043)

val WhatsAppColor = Color(0xFF25D366)
val MessengerColor = Color(0xFF0084FF)
val CellularColor = Color(0xFF90A4AE)
