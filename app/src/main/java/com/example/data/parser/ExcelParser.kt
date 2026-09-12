package com.example.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.local.TimetableEntry
import com.example.utils.DateUtils
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

object ExcelParser {

    private const val TAG = "ExcelParser"

    fun parseExcelFile(context: Context, uri: Uri, fileId: Int): List<TimetableEntry> {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open input stream from Uri: $uri")
        return inputStream.use { stream ->
            parseExcelStream(stream, fileId)
        }
    }

    fun parseExcelFile(file: File, fileId: Int): List<TimetableEntry> {
        if (!file.exists() || file.length() == 0L) {
            throw IllegalArgumentException("File does not exist or is empty: ${file.absolutePath}")
        }
        return parseExcelStream(FileInputStream(file), fileId)
    }

    fun parseExcelStream(inputStream: InputStream, fileId: Int): List<TimetableEntry> {
        val entries = mutableListOf<TimetableEntry>()
        try {
            val workbook = WorkbookFactory.create(inputStream)
            val sheetCount = workbook.numberOfSheets
            Log.d(TAG, "Parsing Excel workbook with $sheetCount sheet(s)...")

            // 1. Identify which sheets belong to "MBA Batch 17 Trim II"
            val targetSheets = mutableListOf<Sheet>()
            for (i in 0 until sheetCount) {
                val sheet = workbook.getSheetAt(i)
                val sheetName = sheet.sheetName
                if (isBatch17Trim1Sheet(sheetName, sheet)) {
                    targetSheets.add(sheet)
                    Log.d(TAG, "Found target sheet for MBA Batch 17 Trim II: '$sheetName'")
                }
            }

            // If no sheet explicitly named Batch 17 Trim I, fallback to all valid schedule sheets
            val sheetsToProcess = if (targetSheets.isNotEmpty()) {
                targetSheets
            } else {
                Log.d(TAG, "No specific Batch 17 sheet name found. Scanning all sheets for schedule headers...")
                val allValid = mutableListOf<Sheet>()
                for (i in 0 until sheetCount) {
                    val sheet = workbook.getSheetAt(i)
                    if (hasScheduleHeaders(sheet)) {
                        allValid.add(sheet)
                    }
                }
                allValid.ifEmpty { (0 until sheetCount).map { workbook.getSheetAt(it) } }
            }

            for (sheet in sheetsToProcess) {
                val sheetEntries = parseSheet(sheet, fileId)
                entries.addAll(sheetEntries)
            }

            workbook.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error while parsing Excel workbook", e)
            throw e
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Sort parsed entries chronologically by date
        return entries.distinctBy { "${it.date}_${it.slot1}_${it.slot2}_${it.slot3}" }
            .sortedBy { it.date }
    }

    /**
     * Checks if a sheet name or its header title belongs to "MBA Batch 17 Trim II".
     */
    private fun isBatch17Trim1Sheet(sheetName: String, sheet: Sheet): Boolean {
        val clean = sheetName.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), " ")
        val has17 = clean.contains("17") || clean.contains("B17") || clean.contains("BATCH 17")
        val hasTrim1 = clean.contains("TRIM I") || clean.contains("TRIM 1") || clean.contains("TRIMI") ||
                clean.contains("TRIM1") || clean.contains("TRI I") || clean.contains("TRI 1") ||
                clean.contains("TRIMESTER I") || clean.contains("TRIMESTER 1") || clean.contains("T 1") ||
                clean.contains("T1")

        if (has17 && hasTrim1) return true
        if (has17 && (clean.contains("TRIM") || clean.contains("TRI") || clean.contains("MBA"))) return true
        if (has17) return true

        // Also check top 8 rows of sheet content for title mentioning Batch 17 Trim I
        return checkSheetHeaderForBatch17(sheet)
    }

    private fun checkSheetHeaderForBatch17(sheet: Sheet): Boolean {
        val maxRows = minOf(sheet.lastRowNum + 1, 10)
        for (r in 0 until maxRows) {
            val row = sheet.getRow(r) ?: continue
            val maxCols = minOf(row.lastCellNum.toInt().coerceAtLeast(0), 20)
            for (c in 0 until maxCols) {
                val txt = getCellValueAsString(row.getCell(c)).uppercase(Locale.US)
                if ((txt.contains("BATCH 17") || txt.contains("B17") || txt.contains("BATCH-17")) &&
                    (txt.contains("TRIM I") || txt.contains("TRIM 1") || txt.contains("TRIM") || txt.contains("TRI"))
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasScheduleHeaders(sheet: Sheet): Boolean {
        val rowIterator = sheet.rowIterator()
        var count = 0
        while (rowIterator.hasNext() && count < 25) {
            val row = rowIterator.next()
            count++
            if (isHeaderRow(row)) return true
        }
        return false
    }

    private fun parseSheet(sheet: Sheet, fileId: Int): List<TimetableEntry> {
        val entries = mutableListOf<TimetableEntry>()
        val rowIterator = sheet.rowIterator()

        var headerRow: Row? = null
        var dayCol = -1
        var dateCol = -1
        var roomCol = -1
        var divCol = -1
        var slot1Col = -1
        var slot2Col = -1
        var slot3Col = -1
        var slot4Col = -1
        var slot5Col = -1

        // 1. Scan for Header Row and Identify Columns Dynamically
        var headerIndex = 0
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            headerIndex++
            if (isHeaderRow(row)) {
                headerRow = row
                // Map columns in this header row
                for (colIdx in 0 until row.lastCellNum) {
                    val cellText = getCellValueAsString(row.getCell(colIdx)).trim().uppercase(Locale.US)
                    if (cellText.isEmpty()) continue

                    // Ignore break/recess columns
                    if (cellText.contains("MIN") || cellText.contains("BREAK") || cellText.contains("INTERVAL") || cellText.contains("LUNCH")) {
                        continue
                    }

                    when {
                        cellText.contains("DAY") -> dayCol = colIdx
                        cellText.contains("DATE") || cellText.contains("DT") -> dateCol = colIdx
                        cellText.contains("ROOM") || cellText.contains("RM NO") || cellText.contains("RM.") || cellText.contains("CLASS") || cellText.contains("HALL") -> roomCol = colIdx
                        cellText.contains("DIV") || cellText.contains("DIVISION") || cellText.contains("SEC") || cellText.contains("SECTION") -> divCol = colIdx
                        cellText.contains("8:30") || cellText.contains("8.30") || cellText.contains("08:30") || cellText.contains("08.30") || (cellText.contains("SLOT") && cellText.contains("1")) -> slot1Col = colIdx
                        cellText.contains("10:15") || cellText.contains("10.15") || (cellText.contains("SLOT") && cellText.contains("2")) -> slot2Col = colIdx
                        cellText.contains("12:00") || cellText.contains("12.00") || cellText.contains("12-") || cellText.contains("12.0") || (cellText.contains("SLOT") && cellText.contains("3")) -> slot3Col = colIdx
                        cellText.contains("14:15") || cellText.contains("14.15") || cellText.contains("2:15") || cellText.contains("2.15") || (cellText.contains("SLOT") && cellText.contains("4")) -> slot4Col = colIdx
                        cellText.contains("16:00") || cellText.contains("16.00") || cellText.contains("4:00") || cellText.contains("4.00") || (cellText.contains("SLOT") && cellText.contains("5")) -> slot5Col = colIdx
                    }
                }
                break
            }
        }

        // If header not detected or missing essential columns, skip sheet
        if (headerRow == null || dayCol == -1 || dateCol == -1 || divCol == -1) {
            Log.d(TAG, "Sheet '${sheet.sheetName}' skipped: Day/Date/Div columns not found.")
            return emptyList()
        }

        // 2. Parse Data Rows
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()

            val divVal = getCellValueAsString(row.getCell(divCol))
            if (!isDivisionA(divVal)) {
                continue // Filter records ONLY for Division A
            }

            val dayVal = getCellValueAsString(row.getCell(dayCol)).trim()
            val dateCell = row.getCell(dateCol)
            val rawDateVal = getCellValueAsString(dateCell).trim()

            // Normalize date
            var normalizedDate: String? = null
            if (dateCell != null && dateCell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    normalizedDate = sdf.format(dateCell.dateCellValue)
                } catch (e: Exception) {
                    // Fallback
                }
            }
            if (normalizedDate == null) {
                normalizedDate = DateUtils.normalizeDate(rawDateVal)
            }

            // Skip rows without valid date
            if (normalizedDate == null) {
                continue
            }

            val roomNo = if (roomCol != -1) getCellValueAsString(row.getCell(roomCol)).trim() else "TBA"
            val finalRoomNo = if (roomNo.isEmpty()) "TBA" else roomNo

            // Extract slot values
            val slot1 = if (slot1Col != -1) getCellValueAsString(row.getCell(slot1Col)).trim() else ""
            val slot2 = if (slot2Col != -1) getCellValueAsString(row.getCell(slot2Col)).trim() else ""
            val slot3 = if (slot3Col != -1) getCellValueAsString(row.getCell(slot3Col)).trim() else ""
            val slot4 = if (slot4Col != -1) getCellValueAsString(row.getCell(slot4Col)).trim() else ""
            val slot5 = if (slot5Col != -1) getCellValueAsString(row.getCell(slot5Col)).trim() else ""

            val finalSlot1 = if (slot1.isEmpty()) "Free Slot" else slot1
            val finalSlot2 = if (slot2.isEmpty()) "Free Slot" else slot2
            val finalSlot3 = if (slot3.isEmpty()) "Free Slot" else slot3
            val finalSlot4 = if (slot4.isEmpty()) "Free Slot" else slot4
            val finalSlot5 = if (slot5.isEmpty()) "Free Slot" else slot5

            // Skip completely blank rows
            if (dayVal.isEmpty() && finalSlot1 == "Free Slot" && finalSlot2 == "Free Slot" && finalSlot3 == "Free Slot") {
                continue
            }

            entries.add(
                TimetableEntry(
                    fileId = fileId,
                    day = if (dayVal.isEmpty()) DateUtils.getDayOfWeekName(normalizedDate) else dayVal,
                    date = normalizedDate,
                    roomNo = finalRoomNo,
                    div = "Division A",
                    slot1 = finalSlot1,
                    slot2 = finalSlot2,
                    slot3 = finalSlot3,
                    slot4 = finalSlot4,
                    slot5 = finalSlot5
                )
            )
        }

        Log.d(TAG, "Extracted ${entries.size} Division A entries from sheet '${sheet.sheetName}'")
        return entries
    }

    private fun isHeaderRow(row: Row): Boolean {
        var hasDay = false
        var hasDate = false
        var hasDivOrSlot = false

        for (colIdx in 0 until row.lastCellNum) {
            val cellVal = getCellValueAsString(row.getCell(colIdx)).trim().uppercase(Locale.US)
            if (cellVal.contains("DAY")) hasDay = true
            if (cellVal.contains("DATE") || cellVal.contains("DT")) hasDate = true
            if (cellVal.contains("DIV") || cellVal.contains("DIVISION") || cellVal.contains("SEC") ||
                cellVal.contains("8:30") || cellVal.contains("8.30") || cellVal.contains("SLOT")
            ) {
                hasDivOrSlot = true
            }
        }
        return hasDay && hasDate && hasDivOrSlot
    }

    private fun isDivisionA(divStr: String): Boolean {
        val clean = divStr.trim().uppercase(Locale.US)
        return clean == "A" ||
                clean == "DIV A" ||
                clean == "DIVISION A" ||
                clean == "DIV-A" ||
                clean == "DIVA" ||
                clean.contains("DIV A") ||
                clean.startsWith("DIV A") ||
                clean.startsWith("DIV-A") ||
                clean.startsWith("DIVISION A") ||
                clean.endsWith(" A") ||
                clean.contains("DIV. A") ||
                clean.contains("SEC A") ||
                clean.contains("SECTION A") ||
                clean == "DIVISION-A"
    }

    fun getCellValueAsString(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        sdf.format(cell.dateCellValue)
                    } catch (e: Exception) {
                        cell.numericCellValue.toString()
                    }
                } else {
                    val num = cell.numericCellValue
                    if (num == num.toInt().toDouble()) {
                        num.toInt().toString()
                    } else {
                        num.toString()
                    }
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue
                } catch (e: Exception) {
                    try {
                        val eval = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                        val value = eval.evaluate(cell)
                        when (value.cellType) {
                            CellType.STRING -> value.stringValue
                            CellType.NUMERIC -> value.numberValue.toInt().toString()
                            else -> ""
                        }
                    } catch (ex: Exception) {
                        ""
                    }
                }
            }
            else -> ""
        }
    }
}
