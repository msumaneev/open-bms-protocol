package com.figago.shared.bms

typealias BmsData = AntBmsStatus

data class AntBmsStatus(
    val soc: Int,
    val voltage: Double,
    val current: Double,
    val temperature: Int?,
    val cellVoltages: List<Double> = emptyList(),
    val temperatures: List<Int> = emptyList(),
    val isChargeMosOpen: Boolean = false,
    val isDischargeMosOpen: Boolean = false,
    val balanceStatus: Int? = null,
    val maxCellVoltage: Double? = null,
    val minCellVoltage: Double? = null,
    val capacityAh: Double? = null,
    val totalCapacityAh: Double? = null,
    val remainingCapacityAh: Double? = null,
    val totalCycleAh: Double? = null
)
