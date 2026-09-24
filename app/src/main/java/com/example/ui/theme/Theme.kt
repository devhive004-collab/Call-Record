package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = when {
        // Honor dynamicColor on Android 12+ when requested.
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            if (darkTheme) androidx.compose.material3.dynamicDarkColorScheme(context)
            else androidx.compose.material3.dynamicLightColorScheme(context)
        }
        darkTheme -> {
            darkColorScheme(
                primary = HighDensityPrimaryDark,
                onPrimary = HighDensityOnPrimaryDark,
                primaryContainer = HighDensityAccentContainerDark,
                onPrimaryContainer = HighDensityOnAccentContainerDark,
                secondary = HighDensityPrimaryDark,
                onSecondary = HighDensityOnPrimaryDark,
                secondaryContainer = HighDensityAccentContainerDark,
                onSecondaryContainer = HighDensityOnAccentContainerDark,
                background = HighDensityBgDark,
                onBackground = HighDensityTextDark,
                surface = HighDensitySurfaceDark,
                onSurface = HighDensityTextDark,
                surfaceVariant = HighDensityBgDark,
                onSurfaceVariant = HighDensitySubTextDark,
                outline = HighDensityBorderDark,
                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005)
            )
        }
        else -> {
            lightColorScheme(
                primary = HighDensityPrimaryLight,
                onPrimary = HighDensityOnPrimaryLight,
                primaryContainer = HighDensityAccentContainerLight,
                onPrimaryContainer = HighDensityOnAccentContainerLight,
                secondary = HighDensityPrimaryLight,
                onSecondary = HighDensityOnPrimaryLight,
                secondaryContainer = HighDensityAccentContainerLight,
                onSecondaryContainer = HighDensityOnAccentContainerLight,
                background = HighDensityBgLight,
                onBackground = HighDensityTextLight,
                surface = HighDensitySurfaceLight,
                onSurface = HighDensityTextLight,
                surfaceVariant = HighDensityBgLight,
                onSurfaceVariant = HighDensitySubTextLight,
                outline = HighDensityBorderLight
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
