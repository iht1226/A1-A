package dev.sleepalarm.sensors

/** Same-process handoff to passive HR callbacks; no historical/test plan is guessed from preferences. */
object CaptureRegistry { @Volatile var active: CaptureSpec? = null }
