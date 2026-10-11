package com.hmaossdetector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale

/**
 * Cross-checks current-user application UID directories against package queries.
 * A UID directory is a process trace; its lifetime depends on the device.
 * Reports observed application UIDs whose package query returns no names.
 * Other users, isolated processes, and SDK sandbox UIDs are excluded.
 */
class HmaScan(private val context: Context) {
    fun run(): ScanReport {
        val userId = Process.myUid() / PER_USER_RANGE
        val root = cgroupRoot()
        val uids = listUidDirectories(root, userId) ?: probeUidDirectories(root, userId)
        val packageManager = context.packageManager
        val observations = uids.orEmpty().asSequence()
            .map { uid -> readObservation(packageManager, uid) }
            .toList()
        val emptyUids = emptyQueryUids(observations, userId)
        val noneNanos = if (uids == null) emptyList() else sampleUnobserved(packageManager, uids.toSet(), userId)
        return ScanReport(
            directories = listOf(directoryRow(root, uids, emptyUids.toSet())),
            emptyUids = emptyUids,
            observations = observations,
            noneNanos = noneNanos,
        )
    }

    private fun readObservation(packageManager: PackageManager, uid: Int): UidObservation {
        val started = System.nanoTime()
        val names = packageManager.getPackagesForUid(uid)
        val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
        return UidObservation(uid, names?.filter { it.isNotBlank() }.orEmpty(), elapsed)
    }

    /** Selects a few candidates with neither an observed UID directory nor a returned package name. */
    private fun sampleUnobserved(packageManager: PackageManager, observed: Set<Int>, userId: Int): List<Long> {
        val samples = ArrayList<Long>(NONE_SAMPLES)
        var attempts = 0
        for (appId in APP_ID_LAST downTo APP_ID_FIRST) {
            val uid = userId * PER_USER_RANGE + appId
            if (uid in observed) continue
            if (samples.size == NONE_SAMPLES || attempts == NONE_CANDIDATES) break
            attempts++
            val observation = readObservation(packageManager, uid)
            if (observation.packages.isEmpty()) samples += observation.elapsedNanos
        }
        return samples
    }

    private fun cgroupRoot(): String = runCatching {
        File("/proc/self/cgroup").useLines { firstCgroupRoot(it) }
    }.getOrDefault("/sys/fs/cgroup/")

    /** Lists only application UIDs belonging to the current Android user. */
    private fun listUidDirectories(root: String, userId: Int): List<Int>? {
        val names = File(root).list() ?: return null
        return names.mapNotNull { name ->
            val uid = name.removePrefix(UID_PREFIX).toIntOrNull()
            if (name.startsWith(UID_PREFIX) && uid != null && isCurrentUserAppUid(uid, userId)) {
                uid
            } else {
                null
            }
        }.distinct().sorted()
    }

    /**
     * Probes individual UIDs when the parent directory cannot be listed.
     * The absent child must return ENOENT and this process's UID must be detectable.
     * These controls reject roots where parent permissions mask every child equally.
     */
    private fun probeUidDirectories(root: String, userId: Int): List<Int>? {
        if (probe("$root$CONTROL_ABSENT_NAME") != PathState.ABSENT) return null
        val self = Process.myUid()
        if (!isCurrentUserAppUid(self, userId) || probe("$root$UID_PREFIX$self") != PathState.PRESENT) {
            return null
        }
        val start = userId * PER_USER_RANGE + APP_ID_FIRST
        val end = userId * PER_USER_RANGE + APP_ID_LAST
        return (start..end).filter { uid ->
            probe("$root$UID_PREFIX$uid") == PathState.PRESENT
        }
    }

    private fun probe(path: String): PathState {
        return try {
            Os.stat(path)
            PathState.PRESENT
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.ENOENT -> PathState.ABSENT
                OsConstants.EACCES, OsConstants.EPERM -> PathState.PRESENT
                else -> PathState.UNKNOWN
            }
        }
    }

    private enum class PathState { PRESENT, ABSENT, UNKNOWN }

    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val APP_ID_FIRST = 10_000
        private const val APP_ID_LAST = 19_999
        private const val UID_PREFIX = "uid_"
        private const val CONTROL_ABSENT_NAME = "uid_not_an_app"
        private const val NONE_SAMPLES = 8
        private const val NONE_CANDIDATES = 32

        /** Android UIDs use userId * 100000 + appId, with application IDs in 10000..19999. */
        fun isCurrentUserAppUid(uid: Int, userId: Int): Boolean {
            if (userId < 0 || uid < 0) return false
            return uid / PER_USER_RANGE == userId && uid % PER_USER_RANGE in APP_ID_FIRST..APP_ID_LAST
        }

        /** Returns distinct observed application UIDs whose package query is empty. */
        fun emptyQueryUids(observations: List<UidObservation>, userId: Int): List<Int> {
            return observations.asSequence()
                .filter { isCurrentUserAppUid(it.uid, userId) && it.packages.isEmpty() }
                .map { it.uid }
                .distinct()
                .sorted()
                .toList()
        }
    }
}

data class ScanReport(
    val directories: List<List<String>> = emptyList(),
    val emptyUids: List<Int> = emptyList(),
    val observations: List<UidObservation> = emptyList(),
    val noneNanos: List<Long> = emptyList(),
    val error: String? = null,
) {
    fun writeTo(bundle: Bundle) {
        bundle.putStringArray(EXTRA_DIRECTORIES, directories.map { it.joinToString("\t") }.toTypedArray())
        bundle.putIntArray(EXTRA_EMPTY, emptyUids.toIntArray())
        bundle.putIntArray(EXTRA_OBSERVED_UIDS, observations.map { it.uid }.toIntArray())
        bundle.putStringArray(EXTRA_PACKAGES, observations.map { it.packages.joinToString("\t") }.toTypedArray())
        bundle.putLongArray(EXTRA_NANOS, observations.map { it.elapsedNanos }.toLongArray())
        bundle.putLongArray(EXTRA_NONE_NANOS, noneNanos.toLongArray())
        bundle.putString(EXTRA_ERROR, error)
    }

    companion object {
        private const val EXTRA_DIRECTORIES = "directories"
        private const val EXTRA_EMPTY = "emptyUids"
        private const val EXTRA_OBSERVED_UIDS = "observedUids"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_NANOS = "nanos"
        private const val EXTRA_NONE_NANOS = "noneNanos"
        private const val EXTRA_ERROR = "error"

        fun from(bundle: Bundle): ScanReport {
            val uids = bundle.getIntArray(EXTRA_OBSERVED_UIDS) ?: intArrayOf()
            val packages = bundle.getStringArray(EXTRA_PACKAGES).orEmpty()
            val nanos = bundle.getLongArray(EXTRA_NANOS) ?: longArrayOf()
            require(uids.size == packages.size && uids.size == nanos.size) { "Incomplete scan measurements." }
            return ScanReport(
                directories = bundle.getStringArray(EXTRA_DIRECTORIES).orEmpty().map { it.split('\t') },
                emptyUids = bundle.getIntArray(EXTRA_EMPTY)?.toList().orEmpty(),
                observations = uids.mapIndexed { index, uid ->
                    UidObservation(uid, packages[index].split('\t').filter { it.isNotBlank() }, nanos[index])
                },
                noneNanos = bundle.getLongArray(EXTRA_NONE_NANOS)?.toList().orEmpty(),
                error = bundle.getString(EXTRA_ERROR),
            )
        }
    }
}

/** Selects the first UID parent reported for this process, without scanning alternate roots. */
internal fun firstCgroupRoot(lines: Sequence<String>): String {
    for (line in lines) {
        val path = line.substringAfterLast(':')
        val marker = path.indexOf("/uid_")
        if (marker >= 0) return "/sys/fs/cgroup${path.substring(0, marker + 1)}"
    }
    return "/sys/fs/cgroup/"
}

internal fun directoryRow(path: String, uids: List<Int>?, emptyUids: Set<Int>): List<String> {
    val name = path.trimEnd('/').ifEmpty { path }
    if (uids == null) return listOf(name, "Unavailable", "-", "-")
    val empty = uids.count { it in emptyUids }
    return listOf(name, uids.size.toString(), (uids.size - empty).toString(), empty.toString())
}

/** Formats measurements only; elapsed times do not determine which UIDs are reported. */
internal fun queryTimingRows(reports: List<ScanReport>): List<List<String>> {
    val latest = reports.lastOrNull() ?: return emptyList()
    if (latest.error != null) return emptyList()
    val observations = reports.flatMap { it.observations }
    return listOf(
        queryTimingRow("None", reports.flatMap { it.noneNanos }),
        queryTimingRow("Named", observations.filter { it.packages.isNotEmpty() }.map { it.elapsedNanos }),
    ) + latest.emptyUids.map { uid ->
        queryTimingRow("UID $uid", observations.filter { it.uid == uid && it.packages.isEmpty() }.map { it.elapsedNanos })
    }
}

private fun queryTimingRow(label: String, samples: List<Long>): List<String> {
    if (samples.isEmpty()) return listOf(label, "-", "-", "-")
    fun micros(nanos: Double): String = String.format(Locale.ROOT, "%.1f", nanos / 1_000.0)
    return listOf(label, micros(samples.average()), micros(samples.min().toDouble()), micros(samples.max().toDouble()))
}

data class UidObservation(val uid: Int, val packages: List<String>, val elapsedNanos: Long = 0L)
