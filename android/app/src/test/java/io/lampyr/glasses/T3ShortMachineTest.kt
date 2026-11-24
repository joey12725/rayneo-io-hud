package io.lampyr.glasses

import io.lampyr.glasses.data.Project
import io.lampyr.glasses.ui.screens.shortMachine
import org.junit.Assert.assertEquals
import org.junit.Test

class T3ShortMachineTest {
    @Test fun everyProjectGetsADeviceNameThatFitsTheRow() {
        assertEquals("HomeDesktop", shortMachine(Project("desktop", "p", "P", "HomeDesktop")))
        assertEquals("Mac", shortMachine(Project("mac", "p", "P", "Alex\u2019s MacBook Air")))
        assertEquals("ga-server", shortMachine(Project("vps", "p", "P", "ga-server")))
        assertEquals("Arcade", shortMachine(Project("arcade", "p", "P", "arcade-cabinet")))
        assertEquals("VPS", shortMachine(Project("vps", "p", "P", "")))
    }
}
