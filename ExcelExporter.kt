package com.example.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.AttendanceRecord
import com.example.data.model.AttendanceStatus
import com.example.data.model.CompanyConstants
import com.example.data.model.DepartmentStat
import com.example.data.model.EmployeeMonthlySummary
import com.example.data.model.OvertimeRecord
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin OpenXML (.xlsx) workbook generator using ZipOutputStream.
 * Generates multi-sheet Excel files compatible with Microsoft Excel, Google Sheets, and WPS Office.
 */
object ExcelExporter {

    data class ExcelSheet(
        val name: String,
        val headers: List<String>,
        val rows: List<List<String>>
    )

    fun buildAttendanceWorkbookSheets(
        reportLabel: String,
        attendanceRecords: List<AttendanceRecord>,
        employeeSummaries: List<EmployeeMonthlySummary>,
        departmentStats: List<DepartmentStat>,
        overtimeRecords: List<OvertimeRecord>
    ): List<ExcelSheet> {
        val dailySheet = ExcelSheet(
            name = "Attendance Log",
            headers = listOf(
                "Date",
                "Employee ID",
                "Employee Name",
                "Department",
                "Shift",
                "Status",
                "Check-In",
                "Check-Out",
                "Worked Time",
                "Method",
                "Notes"
            ),
            rows = attendanceRecords.map { rec ->
                listOf(
                    rec.dateKey,
                    rec.employeeCode,
                    rec.employeeName,
                    rec.department,
                    rec.shiftEnum().label,
                    rec.statusEnum().label,
                    DateTimeUtils.formatTime(rec.checkInTimeMs),
                    DateTimeUtils.formatTime(rec.checkOutTimeMs),
                    DateTimeUtils.formatDurationMinutes(rec.workedMinutes),
                    rec.scanMethodEnum().label,
                    rec.note
                )
            }
        )

        val empSheet = ExcelSheet(
            name = "Employee Summary",
            headers = listOf(
                "Employee ID",
                "Name",
                "Department",
                "Designation",
                "Shift",
                "Present Days",
                "Half Days",
                "Absent Days",
                "Worked Hours",
                "OT Hours"
            ),
            rows = employeeSummaries.map { row ->
                val workedHours = row.totalWorkedMinutes / 60.0
                listOf(
                    row.employee.employeeCode,
                    row.employee.fullName,
                    row.employee.department,
                    row.employee.designation,
                    row.employee.shiftEnum().label,
                    row.presentDays.toString(),
                    row.halfDays.toString(),
                    row.absentDays.toString(),
                    String.format(Locale.US, "%.2f", workedHours),
                    String.format(Locale.US, "%.2f", row.totalOvertimeHours)
                )
            }
        )

        val deptSheet = ExcelSheet(
            name = "Department Report",
            headers = listOf(
                "Department",
                "Active Staff",
                "Present Count",
                "Half-Day Count",
                "Absent Count",
                "On Shift Now",
                "Monthly OT Hours"
            ),
            rows = departmentStats.map { dept ->
                listOf(
                    dept.department,
                    dept.totalEmployees.toString(),
                    dept.presentCount.toString(),
                    dept.halfDayCount.toString(),
                    dept.absentCount.toString(),
                    dept.onShiftCount.toString(),
                    String.format(Locale.US, "%.2f", dept.overtimeHours)
                )
            }
        )

        val otSheet = ExcelSheet(
            name = "Overtime Report",
            headers = listOf(
                "Date",
                "Employee ID",
                "Employee Name",
                "Department",
                "OT Hours",
                "Approval",
                "HOD Edited",
                "Reason"
            ),
            rows = overtimeRecords.map { ot ->
                listOf(
                    ot.dateKey,
                    ot.employeeCode,
                    ot.employeeName,
                    ot.department,
                    String.format(Locale.US, "%.2f", ot.hours),
                    if (ot.approved) "Approved" else "Pending",
                    if (ot.editedByHod) "Yes" else "No",
                    ot.reason
                )
            }
        )

        val metaSheet = ExcelSheet(
            name = "Report Info",
            headers = listOf("Organization", "Report Scope", "Retention Policy", "Generated At"),
            rows = listOf(
                listOf(
                    CompanyConstants.COMPANY_NAME,
                    reportLabel,
                    "Rolling Latest 3 Months (No Annual Backups)",
                    DateTimeUtils.todayDateKey()
                )
            )
        )

        return listOf(dailySheet, empSheet, deptSheet, otSheet, metaSheet)
    }

    fun writeXlsxToStream(outputStream: OutputStream, sheets: List<ExcelSheet>) {
        ZipOutputStream(outputStream).use { zip ->
            zip.putEntryText("[Content_Types].xml", contentTypesXml(sheets.size))
            zip.putEntryText("_rels/.rels", rootRelsXml())
            zip.putEntryText("xl/workbook.xml", workbookXml(sheets))
            zip.putEntryText("xl/_rels/workbook.xml.rels", workbookRelsXml(sheets.size))
            zip.putEntryText("xl/styles.xml", stylesXml())
            sheets.forEachIndexed { index, sheet ->
                zip.putEntryText("xl/worksheets/sheet${index + 1}.xml", worksheetXml(sheet))
            }
        }
    }

    fun createShareableXlsxUri(
        context: Context,
        fileName: String,
        sheets: List<ExcelSheet>
    ): Uri {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.outputStream().use { out ->
            writeXlsxToStream(out, sheets)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun ZipOutputStream.putEntryText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypesXml(sheetCount: Int): String {
        val sheetOverrides = (1..sheetCount).joinToString("\n") { i ->
            """  <Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
$sheetOverrides
</Types>"""
    }

    private fun rootRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(sheets: List<ExcelSheet>): String {
        val sheetNodes = sheets.mapIndexed { idx, s ->
            val safeName = escapeXml(s.name.take(31))
            """    <sheet name="$safeName" sheetId="${idx + 1}" r:id="rId${idx + 1}"/>"""
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
$sheetNodes
  </sheets>
</workbook>"""
    }

    private fun workbookRelsXml(sheetCount: Int): String {
        val sheetRels = (1..sheetCount).joinToString("\n") { i ->
            """  <Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>"""
        }
        val stylesId = sheetCount + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$sheetRels
  <Relationship Id="rId$stylesId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun stylesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF0D5C34"/></patternFill></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
  </cellXfs>
</styleSheet>"""

    private fun worksheetXml(sheet: ExcelSheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")

        // Header Row (style s="1" green bold header)
        sb.append("""<row r="1">""")
        sheet.headers.forEachIndexed { colIdx, header ->
            val ref = "${colLetter(colIdx)}1"
            sb.append("""<c r="$ref" t="inlineStr" s="1"><is><t>${escapeXml(header)}</t></is></c>""")
        }
        sb.append("</row>")

        // Data Rows
        sheet.rows.forEachIndexed { rowIdx, row ->
            val excelRowNum = rowIdx + 2
            sb.append("""<row r="$excelRowNum">""")
            row.forEachIndexed { colIdx, value ->
                val ref = "${colLetter(colIdx)}$excelRowNum"
                sb.append("""<c r="$ref" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>""")
            }
            sb.append("</row>")
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun colLetter(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + (n % 26)))
            n = (n / 26) - 1
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
