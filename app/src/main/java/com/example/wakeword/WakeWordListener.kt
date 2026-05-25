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
    private val wakeWords = listOf("малышка", "малыш", "детка", "эй", "проснись", "компьютер", "привет", "слушай")

    // Добавим callback для отладки
    var onDebugText: ((String) -> Unit)? = null

    fun startListening(): Boolean {
        if (isListening) return true
        
        try {
            if (speechRecognizer == null) {
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
                        val msg = getErrorMessage(error)
                        Log.e("WakeWordListener", "Ошибка: $msg, перезапускаем прослушивание")
                        onDebugText?.invoke("Ошибка микрофона: $msg")
                        isListening = false
                        // НЕ СБРАСЫВАЕМ isCommandMode при некоторых ошибках молчания, но
                        // для надежности оставим как есть, только дадим UI знать об ошибке
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
                        onDebugText?.invoke("Услышал: $bestMatch")
                        
                        var handledAsCommand = false

                        if (isCommandMode) {
                            if (bestMatch.isNotBlank()) {
                                isCommandMode = false
                                handledAsCommand = true
                                onCommandDetected(bestMatch)
                            } else {
                                onDebugText?.invoke("Не расслышала команду, повторите...")
                            }
                        } else {
                            val triggeredWakeWord = wakeWords.firstOrNull { bestMatch.contains(it) }
                            if (triggeredWakeWord != null) {
                                onWakeWordDetected()
                                val command = bestMatch.substringAfter(triggeredWakeWord).trim()
                                if (command.isNotEmpty()) {
                                    handledAsCommand = true
                                    onCommandDetected(command)
                                } else {
                                    isCommandMode = true
                                    onDebugText?.invoke("Активирована! Жду команду...")
                                }
                            }
                        }
                        
                        if (!isCommandMode && !handledAsCommand) {
                            onStateChanged(SpeakerState.IDLE)
                        }
                        
                        isListening = false
                        // Перезапуск слушателя для непрерывного процесса
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, 500)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val bestMatch = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                        if (bestMatch.isNotBlank()) {
                            onDebugText?.invoke("Слушаю: $bestMatch...")
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU") // Прямо просим русский
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // Включаем partial results!
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
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
