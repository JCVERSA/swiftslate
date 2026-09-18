package com.jcversa.swiftslate

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.jcversa.swiftslate.manager.CommandManager
import com.jcversa.swiftslate.manager.HistoryManager
import com.jcversa.swiftslate.manager.StatsManager

class SwiftSlateViewModel(application: Application) : AndroidViewModel(application) {
    val prefs: SharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val keyManager = (application as SwiftSlateApp).keyManager
    val commandManager = CommandManager(application)
    val statsManager = StatsManager(application)
    val historyManager = HistoryManager(application)
}
