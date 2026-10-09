package com.torrent.app.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FtpSafThrottleTest {

    @Test fun withTwoPermitsThirdConcurrentCallWaits() {
        val bothIn = CountDownLatch(2)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val thirdRan = CountDownLatch(1)

        val t1 = Thread {
            FtpSafThrottle.withLimit {
                bothIn.countDown()
                releaseFirst.await()
            }
        }
        val t2 = Thread {
            FtpSafThrottle.withLimit {
                bothIn.countDown()
                releaseSecond.await()
            }
        }
        val t3 = Thread {
            FtpSafThrottle.withLimit { thirdRan.countDown() }
        }

        t1.start(); t2.start()
        assertTrue("two calls should both run", bothIn.await(5, TimeUnit.SECONDS))

        t3.start()
        assertFalse(
            "third call must wait while both permits are held",
            thirdRan.await(500, TimeUnit.MILLISECONDS))

        releaseFirst.countDown()
        assertTrue("third call must run after a permit is freed", thirdRan.await(5, TimeUnit.SECONDS))
        releaseSecond.countDown()
        t1.join(5000); t2.join(5000); t3.join(5000)
    }

    @Test fun withLimitReturnsNullOnTimeout() {
        val release = CountDownLatch(1)
        val t1 = Thread { FtpSafThrottle.withLimit { release.await() } }
        val t2 = Thread { FtpSafThrottle.withLimit { release.await() } }
        t1.start(); t2.start()
        // Wait until both permits are actually held before racing the third call.
        Thread.sleep(300)

        val result = FtpSafThrottle.withLimit(150) { "should-not-run" }

        assertNull("third call must time out (null) while 2 permits are held", result)
        release.countDown()
        t1.join(5000); t2.join(5000)
    }

    @Test fun withLimitReturnsValueAndReleasesPermit() {
        val r1 = FtpSafThrottle.withLimit { 41 + 1 }
        assertEquals(42, r1)
        // After release, a new call succeeds immediately.
        val r2 = FtpSafThrottle.withLimit(100) { "ok" }
        assertEquals("ok", r2)
        // Both permits are free again.
        val l = CountDownLatch(FtpSafThrottle.PERMITS)
        val ts = (1..FtpSafThrottle.PERMITS).map {
            Thread { FtpSafThrottle.withLimit { l.countDown() } }
        }
        ts.forEach { it.start() }
        assertTrue(l.await(5, TimeUnit.SECONDS))
        ts.forEach { it.join(5000) }
    }
}
