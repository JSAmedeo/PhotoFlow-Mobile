package com.photoflowmobile.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColorScheme(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val borderActive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val green: Color,
    val blue: Color,
    val warning: Color,
    val error: Color,
    val cta: Color,
)

val darkAppColors = AppColorScheme(
    background    = Color(0xFF2A2D2E),  // warm charcoal
    surface       = Color(0xFF3D4143),
    surfaceRaised = Color(0xFF4F5456),
    border        = Color(0x14FFFFFF),  // rgba(255,255,255,0.08)
    borderActive  = Color(0x2EFFFFFF),  // rgba(255,255,255,0.18)
    textPrimary   = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC9CDCC),
    textDisabled  = Color(0xFF8A8F8E),
    green         = Color(0xFF6FC79A),  // mint — status / active / healthy
    blue          = Color(0xFF6FC79A),  // mint (same)
    warning       = Color(0xFFFFBB57),
    error         = Color(0xFFE05870),
    cta           = Color(0xFFE5683D),  // orange — primary action buttons only
)

val lightAppColors = AppColorScheme(
    background    = Color(0xFFEFF2F5),
    surface       = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE2E7EC),
    border        = Color(0xFFBFC8D0),
    borderActive  = Color(0xFF8E9AA4),
    textPrimary   = Color(0xFF151D24),
    textSecondary = Color(0xFF4A5660),
    textDisabled  = Color(0xFF8A949C),
    green         = Color(0xFF1E9E32),
    blue          = Color(0xFF1E9E32),
    warning       = Color(0xFFC07A00),
    error         = Color(0xFF9E1E32),
    cta           = Color(0xFFD04A1A),
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors }
