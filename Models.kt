package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole(val label: String) {
    HOD("HOD / Manager"),
    EMPLOYEE("Employee");

    companion object {
        fun fromRaw(raw: String): UserRole =
            entries.firstOrNull { it.name == raw } ?: EMPLOYEE
    }
}

enum class ShiftType(val label: String, val timing: String) {
    GENERAL("General Shift", "09:00 - 18:00"),
    MORNING_A("Shift A (Morning)", "06:00 - 14:00"),
    EVENING_B("Shift B (Evening)", "14:00 - 22:00"),
    NIGHT_C("Shift C (Night)", "22:00 - 06:00");

    companion object {
        fun fromRaw(raw: String): ShiftType =
            entries.firstOrNull { it.name == raw } ?: GENERAL
    }
}

enum class AttendanceStatus(val label: String) {
    PRESENT("Present"),
    HALF_DAY("Half-Day"),
    ABSENT("Absent");

    companion object {
        fun fromRaw(raw: String): AttendanceStatus =
            entries.firstOrNull { it.name == raw } ?: ABSENT
    }
}

enum class ScanMethod(val label: String) {
    QR("QR Scan"),
    MANUAL("Manual"),
    HOD_CORRECTED("HOD Corrected");

    companion object {
        fun fromRaw(raw: String): ScanMethod =
            entries.firstOrNull { it.name == raw } ?: MANUAL
    }
}

@Keep
@IgnoreExtraProperties
data class Employee(
    @DocumentId val id: String = "",
    val userId: String = "",
    val employeeCode: String = "",
    val fullName: String = "",
    val username: String = "",
    val passwordDigest: String = "",
    val role: String = UserRole.EMPLOYEE.name,
    val department: String = "",
    val designation: String = "",
    val shift: String = ShiftType.GENERAL.name,
    val active: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun roleEnum(): UserRole = UserRole.fromRaw(role)
    fun shiftEnum(): ShiftType = ShiftType.fromRaw(shift)
}

@Keep
@IgnoreExtraProperties
data class AttendanceRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val employeeDocId: String = "",
    val employeeCode: String = "",
    val employeeName: String = "",
    val department: String = "",
    val shift: String = ShiftType.GENERAL.name,
    val dateKey: String = "",
    val monthKey: String = "",
    val status: String = AttendanceStatus.PRESENT.name,
    val checkInTimeMs: Long = 0L,
    val checkOutTimeMs: Long = 0L,
    val workedMinutes: Long = 0L,
    val scanMethod: String = ScanMethod.MANUAL.name,
    val note: String = "",
    val correctedByHod: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun statusEnum(): AttendanceStatus = AttendanceStatus.fromRaw(status)
    fun shiftEnum(): ShiftType = ShiftType.fromRaw(shift)
    fun scanMethodEnum(): ScanMethod = ScanMethod.fromRaw(scanMethod)
    val isCurrentlyOnShift: Boolean
        get() = checkInTimeMs > 0L && checkOutTimeMs == 0L && status != AttendanceStatus.ABSENT.name
}

@Keep
@IgnoreExtraProperties
data class OvertimeRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val employeeDocId: String = "",
    val employeeCode: String = "",
    val employeeName: String = "",
    val department: String = "",
    val dateKey: String = "",
    val monthKey: String = "",
    val hours: Double = 0.0,
    val reason: String = "",
    val approved: Boolean = true,
    val editedByHod: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class DepartmentStat(
    val department: String,
    val totalEmployees: Int,
    val presentCount: Int,
    val halfDayCount: Int,
    val absentCount: Int,
    val onShiftCount: Int,
    val overtimeHours: Double
)

data class DashboardMetrics(
    val totalActiveEmployees: Int,
    val presentToday: Int,
    val halfDayToday: Int,
    val absentToday: Int,
    val currentlyOnShift: Int,
    val forgottenCheckoutsCount: Int,
    val totalOvertimeHoursMonth: Double,
    val departmentStats: List<DepartmentStat>
)

data class EmployeeMonthlySummary(
    val employee: Employee,
    val presentDays: Int,
    val halfDays: Int,
    val absentDays: Int,
    val totalWorkedMinutes: Long,
    val totalOvertimeHours: Double
)

object CompanyConstants {
    const val COMPANY_NAME = "HI GREEN CARBON LIMITED"
    val DEFAULT_DEPARTMENTS = listOf(
        "Kiln Operations",
        "Carbon Processing",
        "Quality Control",
        "Maintenance",
        "Logistics & Dispatch",
        "Safety & Environment",
        "Administration"
    )
}
