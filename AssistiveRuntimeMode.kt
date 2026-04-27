package com.circuitsyndicate.findingtheway

enum class AssistiveRuntimeMode(
    val displayName: String,
    val notificationLabel: String
) {
    ASSISTIVE_ACTIVE(
        displayName = "Assistive Active",
        notificationLabel = "Active"
    ),
    ASSISTIVE_SILENT(
        displayName = "Assistive Silent",
        notificationLabel = "Silent"
    ),
    PHONE_PRIORITY(
        displayName = "Phone Priority",
        notificationLabel = "Phone"
    );

    companion object {
        fun fromStoredValue(raw: String?): AssistiveRuntimeMode {
            return entries.firstOrNull { it.name == raw } ?: ASSISTIVE_ACTIVE
        }
    }
}
