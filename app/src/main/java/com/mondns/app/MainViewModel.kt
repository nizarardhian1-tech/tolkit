package com.mondns.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val _statusText = MutableLiveData("Ready")
    val statusText: LiveData<String> get() = _statusText
    fun updateStatus(text: String) { _statusText.value = text }
}
