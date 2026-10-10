package com.ayush.ftpclient

import android.app.Dialog
import android.os.Build
import android.view.WindowManager

object DialogUtils {
    fun applyGlassBlur(dialog: Dialog) {
        val window = dialog.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val params = window.attributes
            params.blurBehindRadius = 40
            window.attributes = params
        }
        window.setDimAmount(0.65f)
    }
}
