package com.example.basicbrowser.repeater.model

import com.example.basicbrowser.proxy.intercept.HttpRequestData
import java.util.UUID

sealed class ExecutionState {
    object Idle : ExecutionState()
    object Executing : ExecutionState()
    object Cancelled : ExecutionState()
    data class Error(val message: String) : ExecutionState()
}

data class RepeaterItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val request: HttpRequestData,
    val response: RepeaterResponse? = null,
    val executionState: ExecutionState = ExecutionState.Idle,
    val createdAt: Long = System.currentTimeMillis()
)

