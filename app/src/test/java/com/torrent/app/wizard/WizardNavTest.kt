package com.torrent.app.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's navigation is pure, so the step order, the boundaries and the "Step N of 5"
 * indicator are pinned here instead of on a device.
 */
class WizardNavTest {

    private val kind = WizardScreenKind.Step(WizardStep.USB)

    @Test fun steps_areTheFiveWizardScreensInOrder() {
        assertEquals(
            listOf(
                WizardStep.USB,
                WizardStep.TRANSMISSION,
                WizardStep.FTP,
                WizardStep.CRAWLER,
                WizardStep.DB_DOWNLOAD,
            ),
            WizardNav.steps,
        )
        assertEquals(5, WizardNav.steps.size)
    }

    @Test fun stepTitles_matchTheScreenHeadings() {
        assertEquals(
            listOf("USB drive", "Transmission", "FTP (Kodi)", "DHT crawler", "Database download"),
            WizardNav.steps.map { it.title },
        )
    }

    @Test fun onlyFtpCrawlerAndTheDownloadStep_areSkippable() {
        assertEquals(
            setOf(WizardStep.FTP, WizardStep.CRAWLER, WizardStep.DB_DOWNLOAD),
            WizardNav.steps.filter { it.isSkippable }.toSet(),
        )
        // USB and Transmission are required (the old form's Finish gating).
        assertFalse(WizardStep.USB.isSkippable)
        assertFalse(WizardStep.TRANSMISSION.isSkippable)
    }

    @Test fun first_isTheUsbStep() {
        assertSame(WizardStep.USB, (WizardNav.first as WizardScreenKind.Step).step)
        assertEquals(WizardStep.USB, WizardNav.first.stepOrNull)
    }

    @Test fun next_walksForwardOneStepAtATime() {
        assertEquals(WizardStep.TRANSMISSION, WizardNav.next(WizardStep.USB.kind).stepOrNull)
        assertEquals(WizardStep.FTP, WizardNav.next(WizardStep.TRANSMISSION.kind).stepOrNull)
        assertEquals(WizardStep.CRAWLER, WizardNav.next(WizardStep.FTP.kind).stepOrNull)
        assertEquals(WizardStep.DB_DOWNLOAD, WizardNav.next(WizardStep.CRAWLER.kind).stepOrNull)
    }

    @Test fun next_onTheLastStep_isANoOp_finishingIsAnExplicitAction() {
        assertEquals(WizardStep.DB_DOWNLOAD.kind, WizardNav.next(WizardStep.DB_DOWNLOAD.kind))
    }

    @Test fun next_onTheProgressScreen_isANoOp_itLeavesBySuccessOrSkip() {
        assertEquals(WizardScreenKind.Progress, WizardNav.next(WizardScreenKind.Progress))
    }

    @Test fun back_walksBackOneStepAtATime() {
        assertEquals(WizardStep.CRAWLER, WizardNav.back(WizardStep.DB_DOWNLOAD.kind).stepOrNull)
        assertEquals(WizardStep.FTP, WizardNav.back(WizardStep.CRAWLER.kind).stepOrNull)
        assertEquals(WizardStep.TRANSMISSION, WizardNav.back(WizardStep.FTP.kind).stepOrNull)
        assertEquals(WizardStep.USB, WizardNav.back(WizardStep.TRANSMISSION.kind).stepOrNull)
    }

    @Test fun back_onTheUsbStep_isANoOp() {
        assertEquals(WizardStep.USB.kind, WizardNav.back(WizardStep.USB.kind))
    }

    @Test fun back_fromProgress_returnsToTheDownloadStepThatStartedIt() {
        assertEquals(WizardStep.DB_DOWNLOAD.kind, WizardNav.back(WizardScreenKind.Progress))
    }

    @Test fun stepIndicator_countsFromOneAndNeverShowsASixthStep() {
        assertEquals("Step 1 of 5", WizardNav.stepIndicator(WizardStep.USB.kind))
        assertEquals("Step 2 of 5", WizardNav.stepIndicator(WizardStep.TRANSMISSION.kind))
        assertEquals("Step 3 of 5", WizardNav.stepIndicator(WizardStep.FTP.kind))
        assertEquals("Step 4 of 5", WizardNav.stepIndicator(WizardStep.CRAWLER.kind))
        assertEquals("Step 5 of 5", WizardNav.stepIndicator(WizardStep.DB_DOWNLOAD.kind))
        // The progress screen belongs to the DB-download step, it is not a 6th one.
        assertEquals("Step 5 of 5", WizardNav.stepIndicator(WizardScreenKind.Progress))
    }

    @Test fun stepNumbers_areOneBased_andTheProgressScreenIsTheLastStep() {
        assertEquals(listOf(1, 2, 3, 4, 5), WizardNav.steps.map { WizardNav.stepNumber(it.kind) })
        assertEquals(5, WizardNav.stepNumber(WizardScreenKind.Progress))
        assertNull(WizardScreenKind.Progress.stepOrNull)
    }

    @Test fun canProceed_requiresTheUsbGrantOnTheUsbStep() {
        assertFalse(WizardNav.canProceed(WizardStep.USB, usbGranted = false))
        assertTrue(WizardNav.canProceed(WizardStep.USB, usbGranted = true))
    }

    @Test fun canProceed_doesNotDependOnTheUsbGrantForTheLaterSteps() {
        // The grant was required to get here; a revoked drive must not re-lock steps 2-5.
        assertTrue(WizardNav.canProceed(WizardStep.TRANSMISSION, usbGranted = false))
        assertTrue(WizardNav.canProceed(WizardStep.FTP, usbGranted = false))
        assertTrue(WizardNav.canProceed(WizardStep.CRAWLER, usbGranted = false))
        assertTrue(WizardNav.canProceed(WizardStep.DB_DOWNLOAD, usbGranted = false))
    }

    @Test fun canProceed_isBlockedByTheStepsValidationErrors() {
        assertFalse(WizardNav.canProceed(WizardStep.USB, usbGranted = true, errors = listOf("no drive")))
        assertFalse(
            WizardNav.canProceed(WizardStep.TRANSMISSION, usbGranted = true, errors = listOf("password too short")),
        )
        assertTrue(WizardNav.canProceed(WizardStep.TRANSMISSION, usbGranted = true, errors = emptyList()))
    }

    @Test fun usbKindRoundTrips() {
        assertEquals(kind, WizardStep.USB.kind)
        assertEquals(WizardStep.USB, kind.stepOrNull)
    }
}
