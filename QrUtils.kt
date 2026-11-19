package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.model.ShiftType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

sealed interface ParsedQrAction {
    data class PlantGateQr(val shiftName: String, val dateKey: String) : ParsedQrAction
    data class EmployeeBadgeQr(val employeeCode: String, val employeeDocId: String) : ParsedQrAction
    data class InvalidQr(val rawValue: String) : ParsedQrAction
}

object QrUtils {
    private const val PLANT_PREFIX = "HIGREEN_GATE"
    private const val EMP_PREFIX = "HIGREEN_EMP"

    fun buildPlantGatePayload(shift: ShiftType, dateKey: String): String =
        "$PLANT_PREFIX|${shift.name}|$dateKey"

    fun buildEmployeeBadgePayload(employeeCode: String, employeeDocId: String): String =
        "$EMP_PREFIX|$employeeCode|$employeeDocId"

    fun parseQrPayload(raw: String): ParsedQrAction {
        val trimmed = raw.trim()
        val parts = trimmed.split("|")
        return when {
            parts.size >= 3 && parts[0] == PLANT_PREFIX -> {
                ParsedQrAction.PlantGateQr(shiftName = parts[1], dateKey = parts[2])
            }
            parts.size >= 3 && parts[0] == EMP_PREFIX -> {
                ParsedQrAction.EmployeeBadgeQr(employeeCode = parts[1], employeeDocId = parts[2])
            }
            else -> ParsedQrAction.InvalidQr(trimmed)
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        val fgColor = Color.rgb(13, 92, 52) // #0D5C34 HI GREEN brand dark green
        val bgColor = Color.WHITE
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix[x, y]) fgColor else bgColor
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
