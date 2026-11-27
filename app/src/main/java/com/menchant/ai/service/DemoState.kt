package com.menchant.ai.service

import kotlinx.coroutines.flow.MutableStateFlow

object DemoState {
    val running = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())

    fun log(line: String) {
        log.value = (log.value + line).takeLast(200)
    }

    fun clear() {
        log.value = emptyList()
    }
}
