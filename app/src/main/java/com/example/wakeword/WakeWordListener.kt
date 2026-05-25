package com.example.wakeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onCommandDetected: (String) -> Unit,
    private val onStateChanged: (SpeakerState) -> Unit,
    private val onRmsLevelChanged: (Float) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isCommandMode = false
    private val wakeWord = "эй малышка"

    fun startListening(): Boolean {
        if (isListening) return true
        
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onStateChanged(SpeakerState.LISTENING)
                    }
                    
                    override fun onBeginningOfSpeech() {}
                    
                    override fun onRmsChanged(rmsdB: Float) {
                        onRmsLevelChanged(rmsdB)
                    }
                    
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    
                    override fun onEndOfSpeech() {
                        // Пользователь закончил говорить
                        if (isCommandMode) {
                            onStateChanged(SpeakerState.THINKING)
                        } else {
                            onStateChanged(SpeakerState.IDLE)
                        }
                    }
                    
                    override fun onError(error: Int) {
                        Log.e("WakeWordListener", "Ошибка: ${getErrorMessage(error)}, перезапускаем прослушивание")
                        isListening = false
                        isCommandMode = false
                        
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            return
                        }
                        
                        // Запускаем переподключение с небольшой задержкой, чтобы избежать ANR
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, 1000)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val bestMatch = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                        Log.d("WakeWordListener", "Услышано: $bestMatch")
                        
                        if (isCommandMode) {
                            // Мы в режиме приема основной команды
                            if (bestMatch.isNotBlank()) {
                                onCommandDetected(bestMatch)
                            }
                            isCommandMode = false
                        } else {
                            // Ожидаем фразу активации
                            if (bestMatch.contains(wakeWord)) {
                                onWakeWordDetected()
                                val command = bestMatch.substringAfter(wakeWord).trim()
                                if (command.isNotEmpty()) {
                                    onCommandDetected(command)
                                } else {
                                    // Переходим в режим слушания команды
                                    isCommandMode = true
                                    isListening = false
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        startListening()
                                    }, 100)
                                    return
                                }
                            }
                        }
                        
                        if (!isCommandMode) {
                            isListening = false
                            onStateChanged(SpeakerState.IDLE)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startListening()
                            }, 500)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            return true
        } catch (e: Exception) {
            Log.e("WakeWord", "Ошибка запуска SpeechRecognizer. Возможно эмулятор без микрофона. ${e.message}")
            isListening = false
            return false
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        onStateChanged(SpeakerState.IDLE)
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио"
            SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет прав"
            SpeechRecognizer.ERROR_NETWORK -> "Ошибка сети"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут сети"
            SpeechRecognizer.ERROR_NO_MATCH -> "Не распознано"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Движок занят"
            SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Долгое молчание"
            else -> "Неизвестная ошибка: $errorCode"
        }
    }
}
