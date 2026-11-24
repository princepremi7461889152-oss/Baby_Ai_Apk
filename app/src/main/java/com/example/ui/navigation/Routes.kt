package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object OnboardingRoute

@Serializable
data object ChatRoute

@Serializable
data object ActionsRoute

@Serializable
data object HistoryRoute

@Serializable
data object MoreRoute

@Serializable
data class RoutinesRoute(val initialTab: Int = 0)

@Serializable
data object RemindersRoute

@Serializable
data object MemoryRoute

@Serializable
data class SettingsRoute(val initialSection: String = "")

@Serializable
data object PermissionsRoute

@Serializable
data object CompareModelsRoute

@Serializable
data object LatencyDebugRoute

@Serializable
data object WakeWordTestRoute

@Serializable
data object RecipeTestRoute

@Serializable
data object CallAssistantRoute

