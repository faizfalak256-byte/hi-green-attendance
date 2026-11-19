package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.AttendanceRecord
import com.example.data.model.AttendanceStatus
import com.example.data.model.Employee
import com.example.data.model.OvertimeRecord
import com.example.data.model.ScanMethod
import com.example.data.model.ShiftType
import com.example.data.model.UserRole
import com.example.util.DateTimeUtils
import com.example.util.PasswordHasher
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Locale

class AttendanceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Workspace Google authentication is required.")

    fun observeEmployees(userId: String): Flow<List<Employee>> = flow {
        val path = "users/$userId/employees"
        emitAll(
            db.collection("users").document(userId).collection("employees")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Employee::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.employeeCode }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeAttendance(userId: String): Flow<List<AttendanceRecord>> = flow {
        val path = "users/$userId/attendance"
        emitAll(
            db.collection("users").document(userId).collection("attendance")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AttendanceRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.dateKey }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeOvertime(userId: String): Flow<List<OvertimeRecord>> = flow {
        val path = "users/$userId/overtime"
        emitAll(
            db.collection("users").document(userId).collection("overtime")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        OvertimeRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.dateKey }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun authenticatePortalUser(
        userId: String,
        rawUsername: String,
        rawPassword: String
    ): Result<Employee> {
        val path = "users/$userId/employees"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Session mismatch." }
            val username = rawUsername.trim().lowercase(Locale.US)
            require(username.length in 3..40) { "Enter a valid username (3-40 characters)." }
            require(rawPassword.length >= 6) { "Password must be at least 6 characters." }

            val querySnapshot = db.collection("users").document(userId)
                .collection("employees")
                .whereEqualTo("username", username)
                .get()
                .await()

            val matched = querySnapshot.toObjects(
                Employee::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).firstOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid username or password."))

            if (!matched.active) {
                return Result.failure(IllegalStateException("Account is deactivated. Contact HOD."))
            }

            if (!PasswordHasher.verify(rawPassword, matched.passwordDigest)) {
                return Result.failure(IllegalArgumentException("Invalid username or password."))
            }

            Result.success(matched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun createEmployee(
        userId: String,
        employeeCode: String,
        fullName: String,
        rawUsername: String,
        rawPassword: String,
        role: UserRole,
        department: String,
        designation: String,
        shift: ShiftType,
        existingEmployees: List<Employee>
    ): Result<Employee> {
        var docPath = "users/$userId/employees"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }

            val code = employeeCode.trim().uppercase(Locale.US)
            val name = fullName.trim()
            val username = rawUsername.trim().lowercase(Locale.US)
            val dept = department.trim()
            val desig = designation.trim()

            require(code.length in 2..32) { "Employee ID must be 2-32 characters." }
            require(name.length in 2..80) { "Employee name must be 2-80 characters." }
            require(username.matches(Regex("^[a-z0-9._-]{3,40}$"))) {
                "Username must be 3-40 chars (lowercase letters, digits, ., _, -)."
            }
            require(rawPassword.length >= 6) { "Password must be at least 6 characters." }
            require(dept.length in 2..60) { "Department must be 2-60 characters." }
            require(desig.length in 2..60) { "Designation must be 2-60 characters." }

            if (existingEmployees.any { it.username.equals(username, ignoreCase = true) }) {
                return Result.failure(IllegalArgumentException("Username '$username' is already assigned."))
            }
            if (existingEmployees.any { it.employeeCode.equals(code, ignoreCase = true) }) {
                return Result.failure(IllegalArgumentException("Employee ID '$code' already exists."))
            }

            val digest = PasswordHasher.hash(rawPassword)
            val docRef = db.collection("users").document(userId).collection("employees").document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to userId,
                "employeeCode" to code,
                "fullName" to name,
                "username" to username,
                "passwordDigest" to digest,
                "role" to role.name,
                "department" to dept,
                "designation" to desig,
                "shift" to shift.name,
                "active" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()

            Result.success(
                Employee(
                    id = docRef.id,
                    userId = userId,
                    employeeCode = code,
                    fullName = name,
                    username = username,
                    passwordDigest = digest,
                    role = role.name,
                    department = dept,
                    designation = desig,
                    shift = shift.name,
                    active = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateEmployee(
        userId: String,
        employeeDocId: String,
        employeeCode: String,
        fullName: String,
        rawUsername: String,
        newRawPassword: String?,
        role: UserRole,
        department: String,
        designation: String,
        shift: ShiftType,
        active: Boolean,
        existingEmployees: List<Employee>
    ): Result<Unit> {
        val docPath = "users/$userId/employees/$employeeDocId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }
            require(employeeDocId.isNotBlank()) { "Invalid employee ID." }

            val code = employeeCode.trim().uppercase(Locale.US)
            val name = fullName.trim()
            val username = rawUsername.trim().lowercase(Locale.US)
            val dept = department.trim()
            val desig = designation.trim()

            require(code.length in 2..32) { "Employee ID must be 2-32 characters." }
            require(name.length in 2..80) { "Employee name must be 2-80 characters." }
            require(username.matches(Regex("^[a-z0-9._-]{3,40}$"))) {
                "Username must be 3-40 chars (lowercase letters, digits, ., _, -)."
            }
            require(dept.length in 2..60) { "Department must be 2-60 characters." }
            require(desig.length in 2..60) { "Designation must be 2-60 characters." }

            if (existingEmployees.any { it.id != employeeDocId && it.username.equals(username, ignoreCase = true) }) {
                return Result.failure(IllegalArgumentException("Username '$username' is used by another employee."))
            }
            if (existingEmployees.any { it.id != employeeDocId && it.employeeCode.equals(code, ignoreCase = true) }) {
                return Result.failure(IllegalArgumentException("Employee ID '$code' is used by another employee."))
            }

            val updates = mutableMapOf<String, Any>(
                "employeeCode" to code,
                "fullName" to name,
                "username" to username,
                "role" to role.name,
                "department" to dept,
                "designation" to desig,
                "shift" to shift.name,
                "active" to active,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!newRawPassword.isNullOrBlank()) {
                require(newRawPassword.length >= 6) { "New password must be at least 6 characters." }
                updates["passwordDigest"] = PasswordHasher.hash(newRawPassword)
            }

            db.document(docPath).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun setEmployeeActive(userId: String, employeeDocId: String, active: Boolean): Result<Unit> {
        val docPath = "users/$userId/employees/$employeeDocId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }
            db.document(docPath).update(
                mapOf(
                    "active" to active,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Records daily check-in using deterministic key `${employee.id}_${dateKey}`
     * so duplicate daily attendance entries are prevented at the database level.
     */
    suspend fun checkIn(
        userId: String,
        employee: Employee,
        scanMethod: ScanMethod,
        dateKey: String = DateTimeUtils.todayDateKey(),
        nowMs: Long = System.currentTimeMillis()
    ): Result<String> {
        val docId = "${employee.id}_$dateKey"
        val docPath = "users/$userId/attendance/$docId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }
            require(employee.active) { "Deactivated employee cannot check in." }

            val docRef = db.document(docPath)
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val existing = existingSnap.toObject(
                    AttendanceRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null && existing.checkInTimeMs > 0L) {
                    return Result.failure(
                        IllegalStateException(
                            "${employee.fullName} already checked in today at ${DateTimeUtils.formatTime(existing.checkInTimeMs)}. Duplicate entry blocked."
                        )
                    )
                }
            }

            val monthKey = DateTimeUtils.formatMonthKey(dateKey)
            val payload = mapOf(
                "userId" to userId,
                "employeeDocId" to employee.id,
                "employeeCode" to employee.employeeCode,
                "employeeName" to employee.fullName,
                "department" to employee.department,
                "shift" to employee.shift,
                "dateKey" to dateKey,
                "monthKey" to monthKey,
                "status" to AttendanceStatus.PRESENT.name,
                "checkInTimeMs" to nowMs,
                "checkOutTimeMs" to 0L,
                "workedMinutes" to 0L,
                "scanMethod" to scanMethod.name,
                "note" to "",
                "correctedByHod" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success("${employee.fullName} checked in at ${DateTimeUtils.formatTime(nowMs)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Completes an active shift check-out, computes worked minutes, evaluates Half-Day (<4.5h) vs Present,
     * and enforces the 3-month rolling retention policy after each completed shift.
     */
    suspend fun checkOut(
        userId: String,
        employee: Employee,
        scanMethod: ScanMethod,
        dateKey: String = DateTimeUtils.todayDateKey(),
        nowMs: Long = System.currentTimeMillis()
    ): Result<String> {
        val docId = "${employee.id}_$dateKey"
        val docPath = "users/$userId/attendance/$docId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }

            val docRef = db.document(docPath)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("No check-in found for ${employee.fullName} today."))
            }
            val existing = snap.toObject(
                AttendanceRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(IllegalStateException("Unable to read attendance record."))

            if (existing.checkOutTimeMs > 0L) {
                return Result.failure(
                    IllegalStateException(
                        "${employee.fullName} already checked out at ${DateTimeUtils.formatTime(existing.checkOutTimeMs)}."
                    )
                )
            }
            if (existing.checkInTimeMs <= 0L) {
                return Result.failure(IllegalStateException("Cannot check out an absent record."))
            }

            val safeOutMs = maxOf(nowMs, existing.checkInTimeMs + 60_000L)
            val workedMins = ((safeOutMs - existing.checkInTimeMs) / 60_000L).coerceIn(1L, 1440L)
            val evaluatedStatus = if (workedMins < 270L) AttendanceStatus.HALF_DAY else AttendanceStatus.PRESENT

            docRef.update(
                mapOf(
                    "checkOutTimeMs" to safeOutMs,
                    "workedMinutes" to workedMins,
                    "status" to evaluatedStatus.name,
                    "scanMethod" to scanMethod.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            // Automatically prune records older than 3 months after completed shift
            pruneOlderThanThreeMonths(userId)

            Result.success(
                "${employee.fullName} checked out (${evaluatedStatus.label}, ${DateTimeUtils.formatDurationMinutes(workedMins)})"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * HOD attendance correction & manual attendance management (for forgotten check-outs, absent, half-day, etc.)
     */
    suspend fun hodSaveAttendance(
        userId: String,
        employee: Employee,
        dateKey: String,
        status: AttendanceStatus,
        checkInTimeMs: Long,
        checkOutTimeMs: Long,
        note: String
    ): Result<Unit> {
        val docId = "${employee.id}_$dateKey"
        val docPath = "users/$userId/attendance/$docId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }

            val cleanNote = note.trim().take(200)
            val effectiveIn = if (status == AttendanceStatus.ABSENT) 0L else checkInTimeMs
            val effectiveOut = if (status == AttendanceStatus.ABSENT) 0L else checkOutTimeMs
            val workedMins = if (effectiveIn > 0L && effectiveOut > effectiveIn) {
                ((effectiveOut - effectiveIn) / 60_000L).coerceIn(0L, 1440L)
            } else {
                0L
            }

            val docRef = db.document(docPath)
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "status" to status.name,
                        "checkInTimeMs" to effectiveIn,
                        "checkOutTimeMs" to effectiveOut,
                        "workedMinutes" to workedMins,
                        "scanMethod" to ScanMethod.HOD_CORRECTED.name,
                        "note" to cleanNote,
                        "correctedByHod" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to userId,
                        "employeeDocId" to employee.id,
                        "employeeCode" to employee.employeeCode,
                        "employeeName" to employee.fullName,
                        "department" to employee.department,
                        "shift" to employee.shift,
                        "dateKey" to dateKey,
                        "monthKey" to DateTimeUtils.formatMonthKey(dateKey),
                        "status" to status.name,
                        "checkInTimeMs" to effectiveIn,
                        "checkOutTimeMs" to effectiveOut,
                        "workedMinutes" to workedMins,
                        "scanMethod" to ScanMethod.HOD_CORRECTED.name,
                        "note" to cleanNote,
                        "correctedByHod" to true,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            pruneOlderThanThreeMonths(userId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun addOvertime(
        userId: String,
        employee: Employee,
        dateKey: String,
        hours: Double,
        reason: String,
        isHodCreator: Boolean
    ): Result<Unit> {
        var docPath = "users/$userId/overtime"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }
            require(hours > 0.0 && hours <= 16.0) { "Overtime hours must be between 0.5 and 16.0." }
            val cleanReason = reason.trim()
            require(cleanReason.length in 2..200) { "Reason must be 2-200 characters." }

            val docRef = db.collection("users").document(userId).collection("overtime").document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to userId,
                "employeeDocId" to employee.id,
                "employeeCode" to employee.employeeCode,
                "employeeName" to employee.fullName,
                "department" to employee.department,
                "dateKey" to dateKey,
                "monthKey" to DateTimeUtils.formatMonthKey(dateKey),
                "hours" to hours,
                "reason" to cleanReason,
                "approved" to isHodCreator,
                "editedByHod" to isHodCreator,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateOvertime(
        userId: String,
        overtimeDocId: String,
        dateKey: String,
        hours: Double,
        reason: String,
        approved: Boolean
    ): Result<Unit> {
        val docPath = "users/$userId/overtime/$overtimeDocId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }
            require(hours > 0.0 && hours <= 16.0) { "Overtime hours must be between 0.5 and 16.0." }
            val cleanReason = reason.trim()
            require(cleanReason.length in 2..200) { "Reason must be 2-200 characters." }

            db.document(docPath).update(
                mapOf(
                    "dateKey" to dateKey,
                    "monthKey" to DateTimeUtils.formatMonthKey(dateKey),
                    "hours" to hours,
                    "reason" to cleanReason,
                    "approved" to approved,
                    "editedByHod" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteOvertime(userId: String, overtimeDocId: String): Result<Unit> {
        val docPath = "users/$userId/overtime/$overtimeDocId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Workspace mismatch." }
            db.document(docPath).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Rolling 3-month retention enforcement: removes attendance and overtime records
     * older than 3 months. Never keeps permanent annual attendance backups.
     */
    suspend fun pruneOlderThanThreeMonths(userId: String): Int {
        val cutoff = DateTimeUtils.threeMonthCutoffDateKey()
        var deletedCount = 0
        try {
            val uid = requireUserId()
            if (uid != userId) return 0

            val oldAtt = db.collection("users").document(userId).collection("attendance")
                .whereLessThan("dateKey", cutoff)
                .get()
                .await()

            val oldOt = db.collection("users").document(userId).collection("overtime")
                .whereLessThan("dateKey", cutoff)
                .get()
                .await()

            val refs = oldAtt.documents.map { it.reference } + oldOt.documents.map { it.reference }
            if (refs.isEmpty()) return 0

            refs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it) }
                batch.commit().await()
                deletedCount += chunk.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$userId/retention_prune")
        }
        return deletedCount
    }
}
