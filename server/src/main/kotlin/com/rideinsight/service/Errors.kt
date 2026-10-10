package com.rideinsight.service

class NotFoundException(message: String) : RuntimeException(message)

class UnsupportedSportException(message: String) : RuntimeException(message)

class InvalidActivityException(message: String) : RuntimeException(message)
