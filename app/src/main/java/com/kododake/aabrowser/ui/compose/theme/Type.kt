/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.ui.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

val ExpressiveTypography = baseTypography().withContentTextDirection()

private fun baseTypography() = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

/**
 * Compose lays text out in the layout direction by default, so on a right-to-left system the
 * app's English strings get RTL paragraphs and their punctuation jumps to the wrong end
 * (".Quick access for the sites you use most"). Letting each text follow its own characters keeps
 * English left-to-right and page titles in Hebrew or Arabic right-to-left.
 */
private fun Typography.withContentTextDirection(): Typography {
    fun TextStyle.contentDirection() = copy(textDirection = TextDirection.Content)
    return copy(
        displayLarge = displayLarge.contentDirection(),
        displayMedium = displayMedium.contentDirection(),
        displaySmall = displaySmall.contentDirection(),
        headlineLarge = headlineLarge.contentDirection(),
        headlineMedium = headlineMedium.contentDirection(),
        headlineSmall = headlineSmall.contentDirection(),
        titleLarge = titleLarge.contentDirection(),
        titleMedium = titleMedium.contentDirection(),
        titleSmall = titleSmall.contentDirection(),
        bodyLarge = bodyLarge.contentDirection(),
        bodyMedium = bodyMedium.contentDirection(),
        bodySmall = bodySmall.contentDirection(),
        labelLarge = labelLarge.contentDirection(),
        labelMedium = labelMedium.contentDirection(),
        labelSmall = labelSmall.contentDirection()
    )
}
