package com.example.diegovocabcards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex

internal class EditorRequest<T : Any>(private val scope: CoroutineScope) {
    var result by mutableStateOf<T?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    var pending: Deferred<T>? = null
        private set

    fun start(retry: Boolean = false, onFailure: T, fetch: suspend () -> T) {
        if (loading || (!retry && result != null)) return
        loading = true
        pending = scope.async {
            try {
                val value = fetch()
                ensureActive()
                result = value
                value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result = onFailure
                onFailure
            } finally {
                loading = false
            }
        }
    }
}

internal class EditorSenseRequests(scope: CoroutineScope, guideScope: CoroutineScope) {
    val google = EditorRequest<List<String>>(scope)
    val translation = EditorRequest<List<String>>(scope)
    val synonyms = EditorRequest<List<String>>(scope)
    val guide = EditorRequest<UsageNoteResult>(guideScope)
}

internal class EditorRequests(
    private val scope: CoroutineScope,
    private val guideScope: CoroutineScope = scope,
    val nano: Mutex = Mutex()
) {
    private val senses = mutableMapOf<Suggestion, EditorSenseRequests>()
    private var savedGuide: Deferred<UsageNoteResult>? = null

    fun retainForSave(guide: Deferred<UsageNoteResult>?): Deferred<UsageNoteResult>? {
        savedGuide = guide
        return guide
    }

    fun closeDraft() {
        for (requests in senses.values) {
            requests.guide.pending?.takeUnless { it === savedGuide }?.cancel()
        }
    }

    fun forSense(sense: Suggestion): EditorSenseRequests =
        senses.getOrPut(sense) { EditorSenseRequests(scope, guideScope) }
}
