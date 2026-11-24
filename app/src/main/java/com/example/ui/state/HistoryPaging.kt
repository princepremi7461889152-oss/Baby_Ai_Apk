package com.example.ui.state

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.data.local.ActionLogEntity
import com.example.features.actionlog.ActionLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

data class ActionLogFilterState(
    val query: String = "",
    val status: String = "ALL",
    val source: String = "ALL",
    val targetApp: String = "ALL",
    val dateFilter: String = "ALL" // ALL, TODAY, 7D
)

fun computeActionLogMinTimestamp(dateFilter: String, nowMs: Long = System.currentTimeMillis()): Long {
    return when (dateFilter) {
        "TODAY" -> Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        "7D" -> nowMs - (7L * 24L * 60L * 60L * 1000L)
        else -> 0L
    }
}

/**
 * True SQLite LIMIT/OFFSET Paging 3 source for Action Log (handles 10,000+ rows without loading all into RAM).
 */
class ActionLogPagingSource(
    private val repository: ActionLogRepository,
    private val filter: ActionLogFilterState
) : PagingSource<Int, ActionLogEntity>() {

    override fun getRefreshKey(state: PagingState<Int, ActionLogEntity>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor)
        return page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ActionLogEntity> {
        val pageIndex = params.key ?: 0
        val pageSize = params.loadSize.coerceIn(1, 100)
        val offset = pageIndex * pageSize
        return try {
            val minTs = computeActionLogMinTimestamp(filter.dateFilter)
            val rows = repository.getPagedFiltered(
                query = filter.query,
                status = filter.status,
                source = filter.source,
                targetApp = filter.targetApp,
                minTimestamp = minTs,
                limit = pageSize,
                offset = offset
            )
            LoadResult.Page(
                data = rows,
                prevKey = if (pageIndex == 0) null else pageIndex - 1,
                nextKey = if (rows.size < pageSize) null else pageIndex + 1
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }
}

/**
 * Lightweight Paging 3 source over in-memory filtered Room flows for Task Runs
 * and unit/UI test previews where no live DAO repository is attached.
 */
class SnapshotListPagingSource<T : Any>(
    private val items: List<T>
) : PagingSource<Int, T>() {

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor)
        return page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val pageIndex = params.key ?: 0
        val pageSize = params.loadSize.coerceAtLeast(1)
        val fromIndex = pageIndex * pageSize
        if (fromIndex >= items.size) {
            return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
        }
        val toIndex = (fromIndex + pageSize).coerceAtMost(items.size)
        val slice = items.subList(fromIndex, toIndex)
        return LoadResult.Page(
            data = slice,
            prevKey = if (pageIndex == 0) null else pageIndex - 1,
            nextKey = if (toIndex < items.size) pageIndex + 1 else null
        )
    }
}

fun <T : Any> createSnapshotPagerFlow(
    items: List<T>,
    pageSize: Int = 25
): Flow<PagingData<T>> {
    return Pager(
        config = PagingConfig(
            pageSize = pageSize,
            enablePlaceholders = false,
            initialLoadSize = pageSize * 2
        ),
        pagingSourceFactory = { SnapshotListPagingSource(items) }
    ).flow
}
