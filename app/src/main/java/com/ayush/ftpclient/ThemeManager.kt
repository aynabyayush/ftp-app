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
    DEFAULT(
        "DEFAULT",
        "Default",
        Color.parseColor("#00F0FF"),
        Color.parseColor("#FF007F"),
        Color.parseColor("#050811"),
        Color.parseColor("#0B162C"),
        Color.parseColor("#02040A")
    ),
    GLASS(
        "GLASS",
        "Glass Morphed",
        Color.parseColor("#00E5FF"),
        Color.parseColor("#9D00FF"),
        Color.parseColor("#040914"),
        Color.parseColor("#0E1D33"),
        Color.parseColor("#060B18")
    ),
    MATERIAL_YOU(
        "MATERIAL_YOU",
        "Material You",
        Color.parseColor("#D0BCFF"),
        Color.parseColor("#A8C7FA"),
        Color.parseColor("#141218"),
        Color.parseColor("#211F26"),
        Color.parseColor("#0F0D13")
    )
}

object ThemeManager {
    private const val PREFS_NAME = "FTP_SETTINGS"
    private const val KEY_THEME = "PREF_THEME"

    fun getSavedTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_THEME, AppTheme.DEFAULT.id) ?: AppTheme.DEFAULT.id
        return AppTheme.entries.firstOrNull { it.id == savedId } ?: AppTheme.DEFAULT
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
