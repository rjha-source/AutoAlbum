package com.autoalbum

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Wakes up whenever a new image appears in MediaStore and sorts it. */
class AutoSortWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("auto", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return Result.success()

        if (Environment.isExternalStorageManager()) {
            val since = prefs.getLong("last_ts", System.currentTimeMillis() / 1000)
            val (maxAdded, photos) = Sorter.query(applicationContext, since)
            for (p in photos) Sorter.process(applicationContext, p)
            // small safety margin so photos that were still "pending" are picked up next time
            prefs.edit().putLong("last_ts", maxOf(since, maxAdded - 10)).apply()
        }

        schedule(applicationContext, append = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "auto_sort"

        fun schedule(context: Context, append: Boolean = false) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                .build()
            val request = OneTimeWorkRequestBuilder<AutoSortWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
