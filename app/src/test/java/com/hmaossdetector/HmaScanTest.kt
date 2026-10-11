package com.hmaossdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HmaScanTest {
    @Test
    fun `an observed application uid with an empty query is reported`() {
        val empty = HmaScan.emptyQueryUids(
            observations = listOf(
                UidObservation(10_042, emptyList()),
                UidObservation(10_053, listOf("com.example.visible")),
            ),
            userId = 0,
        )
        assertEquals(listOf(10_042), empty)
    }

    @Test
    fun `a uid with a returned package name is excluded`() {
        val empty = HmaScan.emptyQueryUids(
            observations = listOf(UidObservation(10_206, listOf("com.example.visible"))),
            userId = 0,
        )
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `empty queries are sorted and duplicate uids appear once`() {
        val empty = HmaScan.emptyQueryUids(
            observations = listOf(
                UidObservation(10_045, emptyList()),
                UidObservation(10_042, emptyList()),
                UidObservation(10_045, emptyList()),
            ),
            userId = 0,
        )
        assertEquals(listOf(10_042, 10_045), empty)
    }

    @Test
    fun `only ordinary application uids of the current user are reported`() {
        val empty = HmaScan.emptyQueryUids(
            observations = listOf(
                UidObservation(1_010_000, emptyList()),
                UidObservation(1_019_999, emptyList()),
                UidObservation(10_042, emptyList()),
                UidObservation(1_020_000, emptyList()),
                UidObservation(1_099_000, emptyList()),
            ),
            userId = 10,
        )
        assertEquals(listOf(1_010_000, 1_019_999), empty)
        assertTrue(HmaScan.isCurrentUserAppUid(1_010_000, 10))
        assertTrue(HmaScan.isCurrentUserAppUid(1_019_999, 10))
        assertFalse(HmaScan.isCurrentUserAppUid(10_042, 10))
        assertFalse(HmaScan.isCurrentUserAppUid(1_020_000, 10))
        assertFalse(HmaScan.isCurrentUserAppUid(-1, 0))
        assertFalse(HmaScan.isCurrentUserAppUid(10_042, -1))
    }

    @Test
    fun `directory statistics separate named and empty results`() {
        val row = directoryRow(
            path = "/sys/fs/cgroup/apps/",
            uids = listOf(10_042, 10_045),
            emptyUids = setOf(10_045),
        )
        assertEquals(listOf("/sys/fs/cgroup/apps", "2", "1", "1"), row)
    }

    @Test
    fun `an inaccessible directory is unavailable rather than empty`() {
        assertEquals(
            listOf("/sys/fs/cgroup/apps", "Unavailable", "-", "-"),
            directoryRow("/sys/fs/cgroup/apps/", null, emptySet()),
        )
    }

    @Test
    fun `an accessible directory with no uids has zero counts`() {
        val row = directoryRow(
            path = "/sys/fs/cgroup/",
            uids = emptyList(),
            emptyUids = emptySet(),
        )
        assertEquals(listOf("/sys/fs/cgroup", "0", "0", "0"), row)
    }

    @Test
    fun `only the first uid parent is selected from multiple cgroup entries`() {
        assertEquals(
            "/sys/fs/cgroup/apps/",
            firstCgroupRoot(sequenceOf("4:cpu:/", "3:memory:/apps/uid_10042/pid_123", "0::/uid_10042/pid_123")),
        )
    }

    @Test
    fun `a missing uid parent uses only the cgroup root`() {
        assertEquals("/sys/fs/cgroup/", firstCgroupRoot(sequenceOf("0::/", "4:cpu:/background")))
    }

    @Test
    fun `query timings aggregate none named and each empty uid separately`() {
        val reports = listOf(
            ScanReport(
                emptyUids = listOf(10_042),
                observations = listOf(
                    UidObservation(10_042, emptyList(), 25_000),
                    UidObservation(10_053, listOf("com.example.visible"), 200_000),
                ),
                noneNanos = listOf(50_000, 90_000),
            ),
            ScanReport(
                emptyUids = listOf(10_042, 10_045),
                observations = listOf(
                    UidObservation(10_042, emptyList(), 75_000),
                    UidObservation(10_045, emptyList(), 120_000),
                    UidObservation(10_053, listOf("com.example.visible"), 400_000),
                ),
                noneNanos = listOf(160_000),
            ),
        )
        assertEquals(
            listOf(
                listOf("None", "100.0", "50.0", "160.0"),
                listOf("Named", "300.0", "200.0", "400.0"),
                listOf("UID 10042", "50.0", "25.0", "75.0"),
                listOf("UID 10045", "120.0", "120.0", "120.0"),
            ),
            queryTimingRows(reports),
        )
    }

    @Test
    fun `timing rows use only the latest empty uid list and empty package samples`() {
        val reports = listOf(
            ScanReport(
                emptyUids = listOf(10_045),
                observations = listOf(
                    UidObservation(10_042, listOf("com.example.visible"), 900_000),
                    UidObservation(10_045, emptyList(), 700_000),
                ),
            ),
            ScanReport(
                emptyUids = listOf(10_042),
                observations = listOf(UidObservation(10_042, emptyList(), 100_000)),
            ),
        )
        val rows = queryTimingRows(reports)
        assertEquals(listOf("UID 10042", "100.0", "100.0", "100.0"), rows.last())
        assertFalse(rows.any { it[0] == "UID 10045" })
    }

    @Test
    fun `revealing five of ten apps updates empty counts and uid rows while directories remain`() {
        val uids = (10_040..10_049).toList()
        fun scan(hiddenCount: Int): ScanReport {
            val observations = uids.mapIndexed { index, uid ->
                UidObservation(
                    uid = uid,
                    packages = if (index < hiddenCount) emptyList() else listOf("com.example.app$index"),
                    elapsedNanos = 1_000L,
                )
            }
            val empty = HmaScan.emptyQueryUids(observations, userId = 0)
            return ScanReport(
                directories = listOf(directoryRow("/sys/fs/cgroup/apps/", uids, empty.toSet())),
                emptyUids = empty,
                observations = observations,
            )
        }

        val previousRounds = List(SAMPLING_ROUNDS) { scan(hiddenCount = 10) }
        val currentRounds = List(SAMPLING_ROUNDS) { scan(hiddenCount = 5) }
        assertEquals(listOf("/sys/fs/cgroup/apps", "10", "0", "10"), previousRounds.last().directories.single())
        assertEquals(listOf("/sys/fs/cgroup/apps", "10", "5", "5"), currentRounds.last().directories.single())
        assertEquals(uids.take(5), currentRounds.last().emptyUids)
        assertEquals(10, queryTimingRows(previousRounds).drop(2).size)
        val expectedLabels = uids.take(5).map { "UID $it" }
        assertEquals(expectedLabels, queryTimingRows(currentRounds).drop(2).map { it.first() })
        // Earlier empty observations must not keep a UID visible once its latest query returns a name.
        assertEquals(expectedLabels, queryTimingRows(previousRounds + currentRounds).drop(2).map { it.first() })
    }

    @Test
    fun `groups without measurements display no values`() {
        assertEquals(
            listOf(listOf("None", "-", "-", "-"), listOf("Named", "-", "-", "-")),
            queryTimingRows(listOf(ScanReport())),
        )
        assertTrue(queryTimingRows(emptyList()).isEmpty())
        assertTrue(queryTimingRows(listOf(ScanReport(error = "Scan failed"))).isEmpty())
    }

    @Test
    fun `zero durations are valid and the mean retains fractional microseconds`() {
        assertEquals(
            listOf("None", "0.5", "0.0", "1.0"),
            queryTimingRows(listOf(ScanReport(noneNanos = listOf(0L, 1_000L)))).first(),
        )
    }
}
