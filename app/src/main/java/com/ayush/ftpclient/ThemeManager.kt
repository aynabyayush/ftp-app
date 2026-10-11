package com.ayush.ftpclient

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.ayush.ftpclient.databinding.ActivityMainBinding

enum class AppTheme(
    val id: String,
    val displayName: String,
    val primaryColor: Int,
    val secondaryColor: Int,
    val startColor: Int,
    val centerColor: Int,
    val endColor: Int
) {
    NEON_ORANGE(
        "NEON_ORANGE",
        "Neon Orange & Blue",
        Color.parseColor("#FF6600"),
        Color.parseColor("#00F0FF"),
        Color.parseColor("#1F0A00"),
        Color.parseColor("#0D1526"),
        Color.parseColor("#020813")
    ),
    NEON_CYBER(
        "NEON_CYBER",
        "Neon Matrix Green",
        Color.parseColor("#00FF66"),
        Color.parseColor("#00F0FF"),
        Color.parseColor("#001F0A"),
        Color.parseColor("#0B261A"),
        Color.parseColor("#021308")
    ),
    NEON_SYNTH(
        "NEON_SYNTH",
        "Synthwave Magenta",
        Color.parseColor("#FF007F"),
        Color.parseColor("#9D00FF"),
        Color.parseColor("#1A0026"),
        Color.parseColor("#200833"),
        Color.parseColor("#0C0214")
    ),
    NEON_AMBER(
        "NEON_AMBER",
        "Amber Firestorm",
        Color.parseColor("#FFB703"),
        Color.parseColor("#FF3D00"),
        Color.parseColor("#261000"),
        Color.parseColor("#331800"),
        Color.parseColor("#140800")
    ),
    NEON_HOLOGRAM(
        "NEON_HOLOGRAM",
        "Cyan Hologram",
        Color.parseColor("#00F0FF"),
        Color.parseColor("#FF007F"),
        Color.parseColor("#001A26"),
        Color.parseColor("#052033"),
        Color.parseColor("#010A14")
    )
}

object ThemeManager {
    private const val PREFS_NAME = "FTP_SETTINGS"
    private const val KEY_THEME = "PREF_THEME"

    fun getSavedTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_THEME, AppTheme.NEON_ORANGE.id) ?: AppTheme.NEON_ORANGE.id
        return AppTheme.entries.firstOrNull { it.id == savedId } ?: AppTheme.NEON_ORANGE
    }

    fun saveTheme(context: Context, theme: AppTheme) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme.id).apply()
    }

    fun applyBackgroundGradient(view: View, theme: AppTheme) {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.startColor, theme.centerColor, theme.endColor)
        )
        view.background = drawable
    }

    fun applyThemeToMainActivity(binding: ActivityMainBinding, theme: AppTheme) {
        applyBackgroundGradient(binding.mainScrollView, theme)
        applyBackgroundGradient(binding.drawerLayout, theme)

        binding.tvMainTitle.setTextColor(theme.primaryColor)
        binding.tvServerCardTitle.setTextColor(theme.primaryColor)
        binding.tvClientCardTitle.setTextColor(theme.primaryColor)

        binding.btnMenu.imageTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.btnHomeSyncProgress.imageTintList = ColorStateList.valueOf(theme.primaryColor)
        binding.chkAnonymous.buttonTintList = ColorStateList.valueOf(theme.primaryColor)

        val primaryBtnDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(theme.primaryColor, theme.secondaryColor)
        ).apply {
            cornerRadius = 30f
        }
        binding.btnToggleServer.background = primaryBtnDrawable
        binding.btnConnectServer.background = primaryBtnDrawable
    }
}
