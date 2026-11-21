package com.capri.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "set a timer for 5 minutes", "wake me up at 7:30"... in her languages. */
class ClockTest {
    private fun timer(s: String) = (Clock.parse(s) as? Clock.Ask.Timer)?.seconds
    private fun alarm(s: String) = (Clock.parse(s) as? Clock.Ask.Alarm)?.let { "%02d:%02d".format(it.hour, it.minute) }

    @Test fun timers() {
        assertEquals(300, timer("set a timer for 5 minutes"))
        assertEquals(300, timer("Capri, timer 5 min"))
        assertEquals(5400, timer("set a timer for 1 hour 30 minutes"))
        assertEquals(45, timer("start a 45 second timer"))
        assertEquals(1200, timer("remind me in 20 minutes"))
        assertEquals(1800, timer("set a timer for half an hour"))
        assertEquals(600, timer("pon un temporizador de 10 minutos"))
        assertEquals(180, timer("mets un minuteur de 3 minutes"))
        assertEquals(120, timer("stell einen Timer für 2 Minuten"))
        assertEquals(900, timer("ustaw minutnik na 15 minut"))
        assertEquals(300, timer("поставь таймер на 5 минут"))
        assertEquals(60, timer("timer for a minute"))
    }

    @Test fun alarms() {
        assertEquals("07:30", alarm("set an alarm for 7:30"))
        assertEquals("07:00", alarm("wake me up at 7 am"))
        assertEquals("18:15", alarm("set an alarm for 6:15 pm"))
        assertEquals("00:00", alarm("alarm at 12 am"))
        assertEquals("21:00", alarm("set an alarm for 9 tonight"))
        assertEquals("06:45", alarm("pon una alarma a las 6:45"))
        assertEquals("07:00", alarm("réveille-moi à 7h"))
        assertEquals("08:00", alarm("stell den Wecker auf 8 Uhr"))
        assertEquals("06:30", alarm("разбуди меня в 6:30"))
    }

    @Test fun notClockStuff() {
        assertNull(Clock.parse("how was your day"))
        assertNull(Clock.parse("I watched a 5 minute video"))
        assertEquals(Clock.Ask.Unclear, Clock.parse("set an alarm"))
    }
}
