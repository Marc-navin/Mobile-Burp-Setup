package com.example.basicbrowser.variation.data

import android.util.Log
import com.example.basicbrowser.proxy.intercept.HttpRequestData
import com.example.basicbrowser.variation.engine.VariationEngine
import com.example.basicbrowser.variation.model.AttackType
import com.example.basicbrowser.variation.model.TargetSummary
import com.example.basicbrowser.variation.model.VariationConfig
import com.example.basicbrowser.variation.model.VariationJobState
import com.example.basicbrowser.variation.model.VariationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VariationRepository(
    private val engine: VariationEngine = VariationEngine(),
    private val maxStoredResults: Int = DEFAULT_MAX_RESULTS
) {
    companion object {
        const val DEFAULT_MAX_RESULTS = 500
        private const val TAG = "VariationRepository"

        @Volatile
        private var instance: VariationRepository? = null

        fun getInstance(): VariationRepository {
            return instance ?: synchronized(this) {
                instance ?: VariationRepository().also { instance = it }
            }
        }
    }

    private val _jobState = MutableStateFlow<VariationJobState>(VariationJobState.Idle)
    val jobState: StateFlow<VariationJobState> = _jobState.asStateFlow()

    private val _results = MutableStateFlow<List<VariationResult>>(emptyList())
    val results: StateFlow<List<VariationResult>> = _results.asStateFlow()

    private val _baseRequest = MutableStateFlow<HttpRequestData?>(null)
    val baseRequest: StateFlow<HttpRequestData?> = _baseRequest.asStateFlow()

    private val _selectedAttackType = MutableStateFlow(AttackType.SNIPER)
    val selectedAttackType: StateFlow<AttackType> = _selectedAttackType.asStateFlow()

    fun setAttackType(attackType: AttackType) {
        _selectedAttackType.value = attackType
    }

    private val internalResults = ArrayList<VariationResult>(maxStoredResults)
    private var pendingTargetSummary: TargetSummary? = null
    private var activeConfig: VariationConfig? = null

    // Single background worker thread for sequential execution
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VariationWorker").apply { isDaemon = true }
    }

    @Synchronized
    fun setBaseRequest(request: HttpRequestData) {
        _baseRequest.value = request
        _jobState.value = VariationJobState.Configuring
    }

    @Synchronized
    fun requestAuthorization(config: VariationConfig): TargetSummary {
        val summary = config.validate()
        activeConfig = config
        _selectedAttackType.value = config.attackType
        pendingTargetSummary = summary
        _jobState.value = VariationJobState.AwaitingConfirmation(summary)
        return summary
    }

    @Synchronized
    fun cancelConfirmation() {
        if (_jobState.value is VariationJobState.AwaitingConfirmation) {
            _jobState.value = VariationJobState.Configuring
        }
    }

    @Synchronized
    fun startConfirmedJob() {
        if (engine.isBusy()) {
            throw IllegalStateException("A Variation job is already in progress.")
        }
        val config = activeConfig ?: throw IllegalStateException("No active variation config configured.")
        val target = pendingTargetSummary ?: throw IllegalStateException("No target summary confirmed.")

        internalResults.clear()
        _results.value = emptyList()
        _jobState.value = VariationJobState.Running(completed = 0, total = target.plannedRequestCount, errors = 0)

        executor.execute {
            engine.runJob(
                config = config,
                target = target,
                onProgress = { result, completed, total, errors ->
                    handleProgress(result, completed, total, errors)
                },
                onFinished = { completed, total, errors, stopped ->
                    handleFinished(completed, total, errors, stopped)
                }
            )
        }
    }

    @Synchronized
    private fun handleProgress(result: VariationResult, completed: Int, total: Int, errors: Int) {
        if (internalResults.size >= maxStoredResults) {
            internalResults.removeAt(0)
        }
        internalResults.add(result)
        _results.value = ArrayList(internalResults)

        // Only update state if still running or paused
        val currentState = _jobState.value
        if (currentState is VariationJobState.Running) {
            _jobState.value = VariationJobState.Running(completed, total, errors)
        } else if (currentState is VariationJobState.Paused) {
            _jobState.value = VariationJobState.Paused(completed, total, errors)
        }
    }

    @Synchronized
    private fun handleFinished(completed: Int, total: Int, errors: Int, stopped: Boolean) {
        if (_jobState.value !is VariationJobState.Running && _jobState.value !is VariationJobState.Paused) {
            return
        }
        if (stopped) {
            _jobState.value = VariationJobState.Stopped(completed, total, errors)
        } else {
            _jobState.value = VariationJobState.Completed(total, errors)
        }
    }

    fun pause() {
        val state = _jobState.value
        if (state is VariationJobState.Running) {
            engine.pause()
            _jobState.value = VariationJobState.Paused(state.completed, state.total, state.errors)
        }
    }

    fun resume() {
        val state = _jobState.value
        if (state is VariationJobState.Paused) {
            engine.resume()
            _jobState.value = VariationJobState.Running(state.completed, state.total, state.errors)
        }
    }

    fun stop() {
        engine.stop()
    }

    @Synchronized
    fun reset() {
        engine.stop()
        internalResults.clear()
        _results.value = emptyList()
        _jobState.value = VariationJobState.Configuring
    }
}

