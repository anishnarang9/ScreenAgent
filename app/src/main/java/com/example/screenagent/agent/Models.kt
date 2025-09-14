package com.example.screenagent.agent

data class Affordance(
    val id: String,
    val text: String? = null,
    val content_desc: String? = null,
    val clazz: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val bounds: List<Int> = emptyList()
)

data class PlanRequest(
    val session_id: String,
    val affordances: List<Affordance>,
    val screenshot_b64: String?,
    val transcript_text: String? = null,
    val is_new_task: Boolean = false
)

data class PlanResponse(
    val action: Map<String, Any?>,
    val isComplete: Boolean? = null
)

