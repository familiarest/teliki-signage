package com.kavabanga.signage.model

data class Screen(
    val id: String = "",
    val slotNumber: Int = 0,
    val schedule: List<ScheduleItem> = emptyList(),
    val updatedAt: Long = 0
)
