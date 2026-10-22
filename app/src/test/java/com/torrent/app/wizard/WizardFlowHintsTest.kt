package com.torrent.app.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hint shown under a step whose Next is disabled, so a greyed-out button always says why.
 *
 * Only the USB step can block Next (its SAF grant is required); every other step validates on
 * the tap and renders its own messages, so it has no hint to show.
 */
class WizardFlowHintsTest {

    @Test fun usbStepWithoutTheGrant_explainsWhatIsMissing() {
        assertEquals(
            "Grant the USB drive to continue.",
            nextDisabledHint(WizardStep.USB, usbGranted = false),
        )
    }

    @Test fun usbStepWithTheGrant_hasNoHint() {
        assertNull(nextDisabledHint(WizardStep.USB, usbGranted = true))
    }

    @Test fun everyOtherStepCanAlwaysAdvance() {
        for (step in WizardStep.entries - WizardStep.USB) {
            assertNull("step=$step", nextDisabledHint(step, usbGranted = false))
        }
    }
}
