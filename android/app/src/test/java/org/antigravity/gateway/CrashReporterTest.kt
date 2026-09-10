package org.antigravity.gateway

import org.antigravity.gateway.util.CrashReportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Android-free parts of [org.antigravity.gateway.util.CrashReporter]: report formatting
 * and retention, i.e. exactly what the on-device recorder relies on.
 */
class CrashReporterTest {

    @Test
    fun testFormatIncludesHeaderExceptionAndStack() {
        val header = "time: 2026-09-10 18:00:00.000 +0800\napp: org.antigravity.gateway 1.0.11\n"
        val throwable = IllegalStateException("keystore unavailable")

        val report = CrashReportFormat.format(header, throwable)

        assertTrue(report.startsWith(header))
        assertTrue(report.contains("exception: java.lang.IllegalStateException: keystore unavailable"))
        assertTrue(report.contains("java.lang.IllegalStateException: keystore unavailable"))
        assertTrue("stack frames should be recorded", report.contains("\tat "))
    }

    @Test
    fun testFormatKeepsCauseChain() {
        val cause = IllegalArgumentException("root cause")
        val wrapper = RuntimeException("wrapper", cause)

        val report = CrashReportFormat.format("header\n", wrapper)

        assertTrue(report.contains("java.lang.RuntimeException: wrapper"))
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
    }

    @Test
    fun testOutdatedReportsKeepsNewest() {
        val names = listOf(
            "crash-20260910-180001-000.txt",
            "crash-20260910-180000-000.txt",
            "crash-20260909-235959-000.txt"
        )

        assertEquals(
            listOf("crash-20260909-235959-000.txt"),
            CrashReportFormat.outdatedReports(names, keep = 2)
        )
        assertEquals(emptyList<String>(), CrashReportFormat.outdatedReports(names, keep = 5))
        assertTrue("timestamps sort chronologically", names.sorted() == names.sortedDescending().reversed())
    }

    @Test
    fun testOutdatedReportsWithSingleSlot() {
        val names = listOf("crash-20260910-180001-000.txt", "crash-20260910-180000-000.txt")

        assertEquals(listOf("crash-20260910-180000-000.txt"), CrashReportFormat.outdatedReports(names, keep = 1))
    }
}
