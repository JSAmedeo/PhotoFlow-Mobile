package com.photoflowmobile.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Semantic palette ──────────────────────────────────────────────────────────
val AppBackground     = Color(0xFF07090B)
val AppSurface        = Color(0xFF101316)
val AppSurfaceRaised  = Color(0xFF181C20)
val AppBorder         = Color(0xFF2A3035)
val AppBorderActive   = Color(0xFF3A4148)

val TextPrimary       = Color(0xFFE8ECEF)
val TextSecondary     = Color(0xFF8E969E)
val TextDisabled      = Color(0xFF5F676E)

val Green             = Color(0xFF7AC36A)   // status indicators only
val Blue              = Color(0xFF5DADEC)   // capture button + brand accent only
val Warning           = Color(0xFFFFB74D)
val Error             = Color(0xFFCF6679)

// ── Legacy aliases (keep old names compiling) ─────────────────────────────────
val DarkBackground      = AppBackground
val DarkSurface         = AppSurface
val DarkSurfaceVariant  = AppSurfaceRaised
val DarkPrimary         = Blue
val DarkOnBackground    = TextPrimary
val DarkOnSurface       = TextPrimary
val DarkError           = Error
val DarkWarning         = Warning
val DarkSuccess         = Green
val DarkBorder          = AppBorder
