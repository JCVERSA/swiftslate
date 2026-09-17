package com.jcversa.swiftslate.ui.processtext

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.SwiftSlateApp
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.CommandManager
import com.jcversa.swiftslate.manager.HistoryManager
import com.jcversa.swiftslate.manager.StatsManager
import com.jcversa.swiftslate.manager.TextStyleTransformer
import com.jcversa.swiftslate.model.Command
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.CommandType
import com.jcversa.swiftslate.service.CommandOutcome
import com.jcversa.swiftslate.service.runTextCommand
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface UiState {
    /**
     * Commands are still being read off disk. The sheet is not shown at all in this state: it
     * used to open at the height of an empty list and then jump taller the moment the commands
     * arrived, which read as a stutter in the middle of the open animation.
     */
    data object Initializing : UiState
    data class CommandList(val commands: List<Command>) : UiState
    data class Loading(val command: Command) : UiState
    data class Preview(
        val result: String,
        val canInsert: Boolean,
        val animateReplacement: Boolean
    ) : UiState
    /** [retry] is null for failures that re-running cannot fix (e.g. nothing configured). */
    data class Error(val message: String, val retry: Command? = null) : UiState
}

/**
 * Turns a tapped command into UI state. The request itself is [runTextCommand] — the same
 * function the accessibility service runs for a typed `?trigger`.
 */
class ProcessTextViewModel(
    app: Application,
    private val selection: Selection
) : AndroidViewModel(app) {

    private companion object {
        const val REQUEST_TIMEOUT_MS = 90_000L
        const val TAG = "ProcessTextViewModel"
    }

    // All lazy: each constructor touches SharedPreferences (and, for KeyManager, the
    // Keystore), and this class is built on the main thread. First touch of each happens
    // inside a Dispatchers.IO block.
    // KeyManager is the process-wide one: benched keys have to be shared with the accessibility
    // service, or this flow re-tries keys that one already knows are rate-limited or invalid.
    private val keyManager by lazy { (app as SwiftSlateApp).keyManager }
    private val commandManager by lazy { CommandManager(app) }
    private val statsManager by lazy { StatsManager(app) }
    private val historyManager by lazy { HistoryManager(app) }
    private val geminiClient by lazy { GeminiClient() }
    private val openAIClient by lazy { OpenAICompatibleClient() }

    private val _uiState = MutableStateFlow<UiState>(UiState.Initializing)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Loaded once; the picker returns to this list rather than re-reading it from disk. */
    private var commands: List<Command> = emptyList()

    /**
     * Gated synchronously in [run] before any suspension point — two rapid taps must not both
     * get past it, which a read-then-update on a StateFlow would allow.
     */
    private val inFlight = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            // SharedPreferences is disk-backed, and viewModelScope runs on
            // Dispatchers.Main.immediate — never touch it on the main thread.
            commands = try {
                withContext(Dispatchers.IO) {
                    // Clipboard/undo built-ins need the live field the accessibility service has
                    // and this flow does not. Local text-style built-ins are the exception: they
                    // transform the selection in this activity without a network request.
                    // Filter by command metadata, not trigger text, because the prefix is user-
                    // configurable.
                    commandManager.getCommands().filterNot {
                        it.isBuiltIn && !TextStyleTransformer.isStyleCommand(it)
                    }
                }
            } catch (e: Exception) {
                // This activity shares the process with the accessibility service — an
                // uncaught exception here would kill the service too (#125). Degrade to an
                // empty list instead.
                Log.w(TAG, "loading commands failed", e)
                emptyList()
            }
            _uiState.value = UiState.CommandList(commands)
        }
    }

    fun run(command: Command) {
        if (!inFlight.compareAndSet(false, true)) return

        // Local commands need no request at all — resolve them without touching the network.
        if (command.type == CommandType.TEXT_REPLACER) {
            val result = TextStyleTransformer.styleFor(command)?.let { style ->
                TextStyleTransformer.transform(style, selection.text)
            } ?: command.prompt
            inFlight.set(false)
            _uiState.value = UiState.Preview(
                result = result,
                canInsert = !selection.readOnly,
                animateReplacement = false
            )
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        statsManager.recordUsage(command.trigger)
                        historyManager.record(
                            command.trigger,
                            selection.text,
                            result,
                            currentProviderForHistory()
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "recording usage failed", e)
                }
            }
            return
        }

        _uiState.value = UiState.Loading(command)
        viewModelScope.launch {
            _uiState.value = try {
                // On IO: KeyManager is Keystore-backed and prefs are disk-backed, both read on
                // whatever dispatcher calls them (the HTTP clients switch to IO themselves).
                val outcome = withTimeout(REQUEST_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        runTextCommand(
                            getApplication<Application>(), keyManager, geminiClient, openAIClient,
                            command.prompt, selection.text
                        )
                    }
                }
                when (outcome) {
                    is CommandOutcome.Success -> {
                        try {
                            withContext(Dispatchers.IO) {
                                statsManager.recordUsage(command.trigger)
                                historyManager.record(
                                    command.trigger,
                                    selection.text,
                                    outcome.text,
                                    currentProviderForHistory()
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "recording usage failed", e)
                        }
                        UiState.Preview(
                            result = outcome.text,
                            canInsert = !selection.readOnly,
                            animateReplacement = command.type == CommandType.AI
                        )
                    }
                    is CommandOutcome.Refusal ->
                        UiState.Error(string(R.string.error_safety_blocked))
                    is CommandOutcome.Unavailable -> UiState.Error(outcome.message)
                    is CommandOutcome.Failure -> UiState.Error(outcome.message, retry = command)
                }
            } catch (_: TimeoutCancellationException) {
                UiState.Error(string(R.string.toast_request_timed_out), retry = command)
            } catch (e: Exception) {
                // Same-process safety net — never let an unexpected failure take the service
                // down with this activity (#125).
                Log.w(TAG, "running command failed unexpectedly", e)
                UiState.Error(string(R.string.error_bad_request), retry = command)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** Returns to the picker, e.g. to apply a different command to the same selection. */
    fun backToCommands() {
        if (inFlight.get()) return
        _uiState.value = UiState.CommandList(commands)
    }

    private fun currentProviderForHistory(): String =
        getApplication<Application>()
            .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString(PrefKeys.PROVIDER_TYPE, "")
            .orEmpty()

    private fun string(resId: Int) = getApplication<Application>().getString(resId)
}
