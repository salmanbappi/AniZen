package tachiyomi.data.history

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.history.repository.ActivityLogRepository
import java.util.Date

class ActivityLogRepositoryImpl(
    private val handler: DatabaseHandler,
) : ActivityLogRepository {

    override suspend fun insert(sourceId: Long, feedId: Long?, animeId: Long?, eventType: Int, count: Long?, timestamp: Date) {
        handler.await {
            activity_logQueries.insert(
                sourceId = sourceId,
                feedId = feedId,
                animeId = animeId,
                eventType = eventType.toLong(),
                count = count,
                timestamp = timestamp,
            )
        }
    }

    override suspend fun getActivityByPeriod(after: Date): List<ActivityLog> {
        return handler.awaitList {
            activity_logQueries.getActivityByPeriod(after) { id, sourceId, feedId, animeId, event_type, count, ts ->
                ActivityLog(id, sourceId, feedId, animeId, event_type.toInt(), count, ts)
            }
        }
    }

    override suspend fun getCountsByPeriod(after: Date): Map<Pair<Long, Int>, Long> {
        // Simplified for stats: Group by (sourceId, eventType) and sum the count/event_count
        return handler.awaitList {
            activity_logQueries.getCountsByPeriod(after)
        }.associate { (source_id, _, event_type, total_count, event_count) ->
            // Use total_count if available (for items), otherwise use event_count (for occurrences)
            val resultCount = total_count ?: event_count
            Pair(source_id, event_type.toInt()) to resultCount.toLong()
        }
    }

    override fun subscribeByPeriod(after: Date): Flow<List<ActivityLog>> {
        return handler.subscribeToList {
            activity_logQueries.getActivityByPeriod(after) { id, sourceId, feedId, animeId, event_type, count, ts ->
                ActivityLog(id, sourceId, feedId, animeId, event_type.toInt(), count, ts)
            }
        }
    }

    override suspend fun removeOldActivity(before: Date) {
        handler.await {
            activity_logQueries.removeOldActivity(before)
        }
    }
}
