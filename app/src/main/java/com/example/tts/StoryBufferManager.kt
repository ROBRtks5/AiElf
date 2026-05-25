package com.example.tts

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class StoryBufferManager(private val voiceManager: VoiceManager) {

    private var storyJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun processStream(textFlow: Flow<String>, onTextUpdate: ((String) -> Unit)? = null) {
        // Прерываем предыдущее чтение
        stop()
        
        storyJob = scope.launch {
            var buffer = StringBuilder()
            var isFirstSentence = true
            var fullTextSoFar = ""

            textFlow
                .catch { e -> Log.e("StoryBuffer", "Ошибка стрима: ${e.message}") }
                .onCompletion { 
                    val remainder = buffer.toString().trim()
                    if (remainder.isNotBlank()) {
                        voiceManager.speak(remainder, flush = false)
                    }
                }
                .collect { chunk ->
                    buffer.append(chunk)
                    fullTextSoFar += chunk
                    onTextUpdate?.invoke(fullTextSoFar)

                    // Пытаемся найти законченные предложения
                    // Регулярное выражение ищет символы конца предложения (точка, восклицание, вопрос).
                    // Для надежности можно использовать простой индекс.
                    
                    var matchFinished = false
                    while (!matchFinished) {
                        val text = buffer.toString()
                        val sentenceEndIndex = text.indexOfAny(charArrayOf('.', '!', '?'))
                        
                        if (sentenceEndIndex != -1 && sentenceEndIndex < text.length) {
                            val sentence = text.substring(0, sentenceEndIndex + 1)
                            buffer = StringBuilder(text.substring(sentenceEndIndex + 1).trimStart())
                            
                            if (sentence.isNotBlank()) {
                                Log.d("StoryBuffer", "Queueing sentence: $sentence")
                                // Первое предложение сразу сбрасывает старую речь, остальные в очередь
                                voiceManager.speak(sentence.trim(), flush = isFirstSentence)
                                isFirstSentence = false
                            }
                        } else {
                            matchFinished = true
                        }
                    }
                }
        }
    }

    fun stop() {
        storyJob?.cancel()
        voiceManager.stop()
    }
}
