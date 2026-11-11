package com.wyrix.budget.presentation.lock

import android.content.Context
import android.content.Intent

object AppRestarter {
    /** Relaunches the app in a fresh process so every repository and database handle is rebuilt. */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
