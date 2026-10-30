package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object LibraryUpdateProgress {

    fun getProgress(context: Context): Flow<Int?> {
        val workQuery = WorkQuery.Builder.fromTags(listOf("AnimeLibraryUpdate"))
            .addStates(listOf(WorkInfo.State.RUNNING))
            .build()

        return context.workManager.getWorkInfosFlow(workQuery)
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@map null
                val progress = workInfo.progress.getInt("progress", -1)
                if (progress != -1) progress else null
            }
    }
}
