package com.example.diegovocabcards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal class EditorRequest<T : Any>(private val scope: CoroutineScope) {
    var result by mutableStateOf<T?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun start(retry: Boolean = false, onFailure: T, fetch: suspend () -> T) {
        if (loading || (!retry && result != null)) return
        loading = true
        scope.launch {
            try {
                val value = fetch()
                ensureActive()
                result = value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = onFailure
            } finally {
                loading = false
            }
        }
    }
}

internal class EditorSenseRequests(scope: CoroutineScope) {
    val google = EditorRequest<List<String>>(scope)
    val translation = EditorRequest<List<String>>(scope)
    val synonyms = EditorRequest<List<String>>(scope)
    val guide = EditorRequest<UsageNoteResult>(scope)
}

internal class EditorRequests(private val scope: CoroutineScope) {
    private val senses = mutableMapOf<Suggestion, EditorSenseRequests>()
    val nano = Mutex()

    fun forSense(sense: Suggestion): EditorSenseRequests =
        senses.getOrPut(sense) { EditorSenseRequests(scope) }
}
