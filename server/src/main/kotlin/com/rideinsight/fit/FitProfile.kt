package com.rideinsight.fit

/**
 * The part of the FIT global profile this project needs: message numbers, field numbers and the
 * scale/offset of the fields used for cycling activities. Numbers follow the public FIT profile
 * (also carried by python-fitparse, MIT); nothing from the Garmin SDK is included.
 */
object FitProfile {
    const val FIELD_TIMESTAMP = 253

    const val MSG_FILE_ID = 0
    const val MSG_SPORT = 12
    const val MSG_SESSION = 18
    const val MSG_LAP = 19
    const val MSG_RECORD = 20
    const val MSG_EVENT = 21
    const val MSG_ACTIVITY = 34

    object FileId {
        const val TYPE = 0
        const val MANUFACTURER = 1
        const val PRODUCT = 2
        const val SERIAL_NUMBER = 3
        const val TIME_CREATED = 4

        const val TYPE_ACTIVITY = 4L
    }

    object Record {
        const val POSITION_LAT = 0
        const val POSITION_LONG = 1
        const val ALTITUDE = 2 // uint16, m = raw / 5 - 500
        const val HEART_RATE = 3
        const val CADENCE = 4
        const val DISTANCE = 5 // uint32, m = raw / 100
        const val SPEED = 6 // uint16, m/s = raw / 1000
        const val POWER = 7
        const val TEMPERATURE = 13
        const val ENHANCED_SPEED = 73 // uint32, m/s = raw / 1000
        const val ENHANCED_ALTITUDE = 78 // uint32, m = raw / 5 - 500
    }

    object Session {
        const val START_TIME = 2
        const val SPORT = 5
        const val SUB_SPORT = 6
        const val TOTAL_ELAPSED_TIME = 7 // uint32, s = raw / 1000
        const val TOTAL_TIMER_TIME = 8 // uint32, s = raw / 1000
        const val TOTAL_DISTANCE = 9 // uint32, m = raw / 100
        const val AVG_SPEED = 14 // uint16, m/s = raw / 1000
        const val AVG_POWER = 20
        const val MAX_POWER = 21
        const val TOTAL_ASCENT = 22
        const val NORMALIZED_POWER = 34
        const val TRAINING_STRESS_SCORE = 35 // uint16, raw / 10
        const val INTENSITY_FACTOR = 36 // uint16, raw / 1000
        const val THRESHOLD_POWER = 45 // first draft said 112; python-fitparse's profile says 45
    }

    object Sport {
        const val SPORT = 0
        const val SUB_SPORT = 1

        const val GENERIC = 0L
        const val RUNNING = 1L
        const val CYCLING = 2L
        const val FITNESS_EQUIPMENT = 4L
    }

    object Event {
        const val EVENT = 0
        const val EVENT_TYPE = 1
        const val DATA = 3
    }

    /** Names for the sport enum values we care about; others are reported by number. */
    fun sportName(code: Long?): String? = when (code) {
        null -> null
        Sport.GENERIC -> "generic"
        Sport.RUNNING -> "running"
        Sport.CYCLING -> "cycling"
        3L -> "transition"
        Sport.FITNESS_EQUIPMENT -> "fitness_equipment"
        5L -> "swimming"
        else -> "sport_$code"
    }
}
