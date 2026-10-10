package com.rideinsight.fit

/** One `record` message reduced to the series this project uses. Nulls mean absent or invalid. */
class FitRecordSample(
    val timestampUnix: Long,
    val power: Int?,
    val heartRate: Int?,
    val cadence: Int?,
    val distanceMeters: Double?,
    val speedMetersPerSecond: Double?,
    val altitudeMeters: Double?,
)

class FitSessionSummary(
    val sport: Long?,
    val subSport: Long?,
    val startTimeUnix: Long?,
    val totalElapsedSecs: Double?,
    val totalTimerSecs: Double?,
    val totalDistanceMeters: Double?,
    val avgPower: Int?,
    val maxPower: Int?,
    val normalizedPower: Int?,
    val trainingStressScore: Double?,
    val intensityFactor: Double?,
    val thresholdPower: Int?,
    val totalAscentMeters: Int?,
)

class FitFileId(
    val type: Long?,
    val manufacturer: Long?,
    val product: Long?,
    val serialNumber: Long?,
    val timeCreatedUnix: Long?,
)

/** An activity file: file id, sessions and the record series in file order. */
class FitActivity(
    val fileId: FitFileId?,
    val sessions: List<FitSessionSummary>,
    val sports: List<Long?>,
    val records: List<FitRecordSample>,
    val crcValid: Boolean,
    val developerFieldDefinitions: Int,
) {
    /** Sport of the first session, else the first `sport` message. */
    val sport: Long? get() = sessions.firstOrNull()?.sport ?: sports.firstOrNull()

    companion object {
        fun from(file: FitFile): FitActivity {
            val fileId = file.messages(FitProfile.MSG_FILE_ID).firstOrNull()?.let {
                FitFileId(
                    type = it.int(FitProfile.FileId.TYPE),
                    manufacturer = it.int(FitProfile.FileId.MANUFACTURER),
                    product = it.int(FitProfile.FileId.PRODUCT),
                    serialNumber = it.int(FitProfile.FileId.SERIAL_NUMBER),
                    timeCreatedUnix = it.int(FitProfile.FileId.TIME_CREATED)?.plus(FitDecoder.FIT_EPOCH_OFFSET),
                )
            }
            val sessions = file.messages(FitProfile.MSG_SESSION).map { s ->
                FitSessionSummary(
                    sport = s.int(FitProfile.Session.SPORT),
                    subSport = s.int(FitProfile.Session.SUB_SPORT),
                    startTimeUnix = s.int(FitProfile.Session.START_TIME)?.plus(FitDecoder.FIT_EPOCH_OFFSET),
                    totalElapsedSecs = s.scaled(FitProfile.Session.TOTAL_ELAPSED_TIME, 1000.0),
                    totalTimerSecs = s.scaled(FitProfile.Session.TOTAL_TIMER_TIME, 1000.0),
                    totalDistanceMeters = s.scaled(FitProfile.Session.TOTAL_DISTANCE, 100.0),
                    avgPower = s.int(FitProfile.Session.AVG_POWER)?.toInt(),
                    maxPower = s.int(FitProfile.Session.MAX_POWER)?.toInt(),
                    normalizedPower = s.int(FitProfile.Session.NORMALIZED_POWER)?.toInt(),
                    trainingStressScore = s.scaled(FitProfile.Session.TRAINING_STRESS_SCORE, 10.0),
                    intensityFactor = s.scaled(FitProfile.Session.INTENSITY_FACTOR, 1000.0),
                    thresholdPower = s.int(FitProfile.Session.THRESHOLD_POWER)?.toInt(),
                    totalAscentMeters = s.int(FitProfile.Session.TOTAL_ASCENT)?.toInt(),
                )
            }
            val sports = file.messages(FitProfile.MSG_SPORT).map { it.int(FitProfile.Sport.SPORT) }
            val records = ArrayList<FitRecordSample>()
            for (r in file.messages(FitProfile.MSG_RECORD)) {
                val ts = r.timestampUnix ?: continue // a record without a timestamp cannot be placed
                records.add(
                    FitRecordSample(
                        timestampUnix = ts,
                        power = r.int(FitProfile.Record.POWER)?.toInt(),
                        heartRate = r.int(FitProfile.Record.HEART_RATE)?.toInt(),
                        cadence = r.int(FitProfile.Record.CADENCE)?.toInt(),
                        distanceMeters = r.scaled(FitProfile.Record.DISTANCE, 100.0),
                        speedMetersPerSecond = r.scaled(FitProfile.Record.ENHANCED_SPEED, 1000.0)
                            ?: r.scaled(FitProfile.Record.SPEED, 1000.0),
                        altitudeMeters = r.scaled(FitProfile.Record.ENHANCED_ALTITUDE, 5.0, 500.0)
                            ?: r.scaled(FitProfile.Record.ALTITUDE, 5.0, 500.0),
                    ),
                )
            }
            return FitActivity(fileId, sessions, sports, records, file.crcValid, file.developerFieldDefinitions)
        }

        fun decode(bytes: ByteArray): FitActivity = from(FitDecoder.decode(bytes))
    }
}
