package com.prakash.phub

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object FileSharingRegistry {
    val selectedFiles = mutableStateListOf<Uri>()
    var secretCode by mutableStateOf("")
    var allCodes by mutableStateOf<List<String>>(emptyList())
    var fullAccessMode by mutableStateOf(false)
}
