package com.example

/** Main-thread generation guard: a cleared or superseded operation cannot republish secrets. */
internal class SensitiveOperationGate {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun invalidate() { generation++ }
    fun isCurrent(token: Long): Boolean = token == generation
}
