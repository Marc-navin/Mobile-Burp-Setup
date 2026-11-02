package com.example.basicbrowser.variation.model

/**
 * Lifecycle states of a Request Variation run.
 */
sealed class VariationJobState {
    object Idle : VariationJobState()
    object Configuring : VariationJobState()
    data class AwaitingConfirmation(val summary: TargetSummary) : VariationJobState()
    data class Running(val completed: Int, val total: Int, val errors: Int) : VariationJobState()
    data class Paused(val completed: Int, val total: Int, val errors: Int) : VariationJobState()
    data class Stopped(val completed: Int, val total: Int, val errors: Int) : VariationJobState()
    data class Completed(val total: Int, val errors: Int) : VariationJobState()
}

