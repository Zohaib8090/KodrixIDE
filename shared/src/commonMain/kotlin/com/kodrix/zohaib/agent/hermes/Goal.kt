package com.kodrix.zohaib.agent.hermes

@kotlinx.serialization.Serializable
data class Goal(
    val id: String,
    val description: String,
    val status: Status = Status.PENDING,
    val createdAt: Long = currentTimeMillis(),
    val completedAt: Long? = null,
) {
    enum class Status { PENDING, ACTIVE, COMPLETED, FAILED, ABANDONED }

    fun isTerminal(): Boolean = status == Status.COMPLETED || status == Status.FAILED || status == Status.ABANDONED
}
