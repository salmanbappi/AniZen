// ANZ -->
package eu.kanade.tachiyomi.data.coil

import kotlinx.coroutines.CompletableDeferred

/**
 * De-duplicates concurrent requests for the same key.
 * The first caller runs [block]. If it succeeds, waiting callers run [block] too
 * (to read the cached result). If it fails or is cancelled, one of the waiting callers runs it.
 */
class DeDupeConcurrentRequests<T>(
    private val isSuccess: (T) -> Boolean = { true },
) {
    private val requests = mutableMapOf<Any, CompletableDeferred<Boolean>>() // ANZ

    suspend fun apply(key: Any, block: suspend () -> T): T {
        while (true) {
            var isLeader = false
            val request = synchronized(requests) {
                requests.getOrPut(key) {
                    isLeader = true
                    CompletableDeferred()
                }
            }

            if (!isLeader) {
                if (request.await()) return block()
                continue
            }

            var succeeded = false
            try {
                return block().also { succeeded = isSuccess(it) }
            } finally {
                synchronized(requests) {
                    requests -= key
                }
                request.complete(succeeded)
            }
        }
    }
}
// ANZ <--
