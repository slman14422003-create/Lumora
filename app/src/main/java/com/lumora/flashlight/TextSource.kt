package com.lumora.flashlight

import android.content.Context
import androidx.annotation.StringRes

interface TextSource {
    fun get(@StringRes id: Int, vararg args: Any): String
}

class ContextText(private val context: Context) : TextSource {
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
}
