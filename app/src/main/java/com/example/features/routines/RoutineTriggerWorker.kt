package com.example.features.routines

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.HistoryRepository
import com.example.data.local.RoutineDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class RoutineTriggerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun routineDao(): RoutineDao
        fun routineManager(): RoutineManager
        fun historyRepository(): HistoryRepository
    }

    override suspend fun doWork(): Result {
        val routineId = inputData.getLong("routine_id", -1L)
        if (routineId <= 0L) return Result.failure()

        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val routine = ep.routineDao().getEnabledRoutines().firstOrNull { it.id == routineId }
            ?: return Result.success()

        ep.routineManager().runRoutine(routine) { cmd ->
            ep.historyRepository().enqueueForUnlock(cmd, "routine_worker")
        }
        return Result.success()
    }
}
