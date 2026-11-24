package com.example.ui.state

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight Paging 3 source over in-memory filtered Room flows so large Action Log
 * and Task Run lists paginate smoothly without altering existing DAO signatures.
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
