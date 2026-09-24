package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.database.Recording
import com.example.data.database.RecordingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(
    context: Context,
    private val recordingDao: RecordingDao
) {
    private val TAG = "RecordingRepository"
    private val appContext = context.applicationContext
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun getRecordingById(id: Long): Recording? = withContext(Dispatchers.IO) {
        recordingDao.getRecordingById(id)
    }

    suspend fun insert(recording: Recording): Long = withContext(Dispatchers.IO) {
        recordingDao.insertRecording(recording)
    }

    suspend fun update(recording: Recording) = withContext(Dispatchers.IO) {
        recordingDao.updateRecording(recording)
    }

    suspend fun delete(recording: Recording): Boolean = withContext(Dispatchers.IO) {
        // Delete physical file if it exists
        var fileDeleted = true
        try {
            val file = File(recording.filePath)
            if (file.exists()) {
                fileDeleted = file.delete()
                if (!fileDeleted) {
                    Log.w(TAG, "Failed to delete audio file: ${recording.filePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audio file", e)
            fileDeleted = false
        }
        try {
            recordingDao.deleteRecording(recording)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting DB row id=${recording.id}", e)
            return@withContext false
        }
        // Return true if DB row gone; file failure is logged but does not
        // block DB cleanup (avoids orphan rows). Caller can retry file sweep.
        true
    }

    suspend fun deleteById(id: Long): Boolean = withContext(Dispatchers.IO) {
        val recording = recordingDao.getRecordingById(id) ?: return@withContext false
        // Reuse delete() logic without nested withContext deadlock (still IO).
        var fileDeleted = true
        try {
            val file = File(recording.filePath)
            if (file.exists()) fileDeleted = file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audio file for id=$id", e)
            fileDeleted = false
        }
        val rows = try {
            recordingDao.deleteRecordingById(id)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting DB row id=$id", e)
            return@withContext false
        }
        rows > 0
    }

    fun search(query: String): Flow<List<Recording>> {
        if (query.isBlank()) return allRecordings
        return recordingDao.searchRecordings(escapeLike(query))
    }

    private fun escapeLike(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '%' || c == '_' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    // Remove seed prepopulation and clear any existing seeds from first installation
    suspend fun prepopulateIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasCleanedSeeds = prefs.getBoolean(KEY_CLEANED_SEEDS, false)
        if (!hasCleanedSeeds) {
            try {
                // Single bulk delete instead of N+1 get+delete loop.
                recordingDao.deleteByPathFragment("seed_")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning seed recordings", e)
            }
            prefs.edit()
                .putBoolean(KEY_CLEANED_SEEDS, true)
                .putBoolean(KEY_PREPOPULATED, true)
                .apply()
        }
    }

    /**
     * Drops DB rows whose audio file is gone (uninstall/reinstall, manual
     * deletion, cloud-backup restore of DB without files, 0-byte corpses).
     * Returns number of rows removed. Safe to run on every startup.
     */
    suspend fun cleanupMissingFiles(): Int = withContext(Dispatchers.IO) {
        var removed = 0
        try {
            val current = recordingDao.getAllRecordings().first()
            for (rec in current) {
                try {
                    val f = File(rec.filePath)
                    if (!f.exists() || f.length() == 0L) {
                        try {
                            recordingDao.deleteRecording(rec)
                            removed++
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting phantom row id=${rec.id}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking file for id=${rec.id}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cleanupMissingFiles failed", e)
        }
        if (removed > 0) Log.i(TAG, "cleanupMissingFiles removed $removed phantom rows")
        removed
    }

    companion object {
        private const val PREFS_NAME = "call_recorder_prefs"
        private const val KEY_CLEANED_SEEDS = "cleaned_seeds_v2"
        private const val KEY_PREPOPULATED = "prepopulated_v1"
    }
}
