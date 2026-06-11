package com.kavabanga.signage.model

data class ScheduleItem(
    val mediaUrl: String = "",
    val mediaType: String = "",     // "image" or "video"
    val fileName: String = "",
    val hasSchedule: Boolean = false,
    val endTime: String? = null      // "HH:mm" format
)
