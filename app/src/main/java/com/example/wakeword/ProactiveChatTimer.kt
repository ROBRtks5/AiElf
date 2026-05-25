package com.example.wakeword

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ProactiveChatTimer {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // В миллисекундах (3 минуты) - для тестов можно поставить меньше, но по заданию 3-5 мин.
    private val PING_INTERVAL = 3 * 60 * 1000L

    private val _proactiveTrigger = MutableSharedFlow<Unit>()
    val proactiveTrigger = _proactiveTrigger.asSharedFlow()

    fun resetTimer() {
        job?.cancel()
        job = scope.launch {
            delay(PING_INTERVAL)
            _proactiveTrigger.emit(Unit)
        }
    }

    fun stopTimer() {
        job?.cancel()
    }
}
