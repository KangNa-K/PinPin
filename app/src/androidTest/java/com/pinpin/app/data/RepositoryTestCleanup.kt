package com.pinpin.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * Production singletons live for the process; test instances must stop before
 * their temporary databases close. Keep this access confined to debug tests.
 */
internal suspend fun stopRepositoryCollectors(repository: Any) {
    val field = repository.javaClass.getDeclaredField("applicationScope")
    field.isAccessible = true
    val scope = field.get(repository) as CoroutineScope
    requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
}
