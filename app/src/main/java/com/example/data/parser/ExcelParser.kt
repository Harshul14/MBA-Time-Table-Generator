package com.example.data.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.local.TimetableEntry
import com.example.utils.DateUtils
import org.apache.poi.ss.usermodel.*
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

object ExcelParser {

    private const val TAG = "ExcelParser"

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * DIVISION CONFIGURATION — THE ONLY PLACE YOU NEED TO CHANGE
     * ─────────────────────────────────────────────────────────────────────────
     * Change this letter to switch the entire app to a different division.
     * Valid values: "A", "B", "C", "D", "E" (must match what is in the Excel).
     * ─────────────────────────────────────────────────────────────────────────
     */
    const val DEFAULT_DIVISION = "A"

    /**
     * Result returned from all parse entry-points.
     * @param entries Filtered timetable entries for the selected division.
     * @param detectedTrimester Human-readable label e.g. "Trim II", "Trim IV".
     * @param availableDivisions All unique division letters found in the workbook (e.g. ["A","B","C"]).
     */
    data class ParseResult(
        val entries: List<TimetableEntry>,
        val detectedTrimester: String,
        val availableDivisions: List<String>
    )

    // ─────────────────────────────────────────────────────────────
    // Public entry-points
    // ─────────────────────────────────────────────────────────────

    fun parseExcelFile(
        context: Context,
        uri: Uri,
        fileId: Int,
        selectedDivision: String = DEFAULT_DIVISION
    ): ParseResult {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open input stream from Uri: $uri")
        return inputStream.use { parseExcelStream(it, fileId, selectedDivision) }
    }

    fun parseExcelFile(
        file: File,
        fileId: Int,
        selectedDivision: String = DEFAULT_DIVISION
    ): ParseResult {
        if (!file.exists() || file.length() == 0L)
            throw IllegalArgumentException("File does not exist or is empty: ${file.absolutePath}")
        return parseExcelStream(FileInputStream(file), fileId, selectedDivision)
    }

    fun parseExcelStream(
        inputStream: InputStream,
        fileId: Int,
        selectedDivision: String = DEFAULT_DIVISION
    ): ParseResult {
        val entries = mutableListOf<TimetableEntry>()
        var detectedTrimester = "Trim (Auto)"
        val allDivisions = mutableSetOf<String>()

        try {
            val workbook = WorkbookFactory.create(inputStream)
            val sheetCount = workbook.numberOfSheets
            Log.d(TAG, "Parsing workbook with $sheetCount sheet(s) for Division '$selectedDivision'…")

            // 1. Identify target sheets (MBA Batch 17, any trimester)
            val targetSheets = (0 until sheetCount)
                .map { workbook.getSheetAt(it) }
                .filter { isBatch17AnyTrimSheet(it.sheetName, it) }
                .also { list ->
                    list.forEach { Log.d(TAG, "Target sheet: '${it.sheetName}'") }
                }

            // Fallback: any sheet that has recognisable schedule headers
            val sheetsToProcess = targetSheets.ifEmpty {
                Log.d(TAG, "No Batch 17 sheet found. Falling back to header scan…")
                (0 until sheetCount)
                    .map { workbook.getSheetAt(it) }
                    .filter { hasScheduleHeaders(it) }
                    .ifEmpty { (0 until sheetCount).map { workbook.getSheetAt(it) } }
            }

            // 2. Detect trimester from first matching sheet
            sheetsToProcess.firstOrNull()?.let { sheet ->
                val label = detectTrimesterLabel(sheet.sheetName, sheet)
                if (label != "Trim (Auto)") detectedTrimester = label
            }

            // 3. Parse each sheet
            for (sheet in sheetsToProcess) {
                val (sheetEntries, sheetDivisions) = parseSheet(sheet, fileId, selectedDivision)
                entries.addAll(sheetEntries)
                allDivisions.addAll(sheetDivisions)
            }

            workbook.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing workbook", e)
            throw e
        } finally {
            try { inputStream.close() } catch (_: Exception) { }
        }

        val result = ParseResult(
            entries = entries
                .distinctBy { "${it.date}_${it.slot1}_${it.slot2}_${it.slot3}" }
                .sortedBy { it.date },
            detectedTrimester = detectedTrimester,
            availableDivisions = allDivisions.sorted()
        )
        Log.d(TAG, "ParseResult: trimester='${result.detectedTrimester}', divisions=${result.availableDivisions}, entries=${result.entries.size}")
        return result
    }

    /**
     * Scans the workbook (without filtering) and returns all unique division letters found.
     * Useful for populating a division selector before the user chooses one.
     */
    fun getAvailableDivisions(inputStream: InputStream): List<String> {
        val divisions = mutableSetOf<String>()
        try {
            val workbook = WorkbookFactory.create(inputStream)
            val sheetCount = workbook.numberOfSheets
            val sheets = (0 until sheetCount)
                .map { workbook.getSheetAt(it) }
                .filter { isBatch17AnyTrimSheet(it.sheetName, it) }
                .ifEmpty {
                    (0 until sheetCount).map { workbook.getSheetAt(it) }
                        .filter { hasScheduleHeaders(it) }
                }

            for (sheet in sheets) {
                val divCol = findDivCol(sheet) ?: continue
                val rowIterator = sheet.rowIterator()
                var headerPassed = false
                while (rowIterator.hasNext()) {
                    val row = rowIterator.next()
                    if (!headerPassed) {
                        if (isHeaderRow(row)) headerPassed = true
                        continue
                    }
                    val normalized = normalizeDiv(getCellValueAsString(row.getCell(divCol)))
                    if (normalized.isNotEmpty()) divisions.add(normalized)
                }
            }
            workbook.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning divisions", e)
        } finally {
            try { inputStream.close() } catch (_: Exception) { }
        }
        return divisions.sorted()
    }

    // ─────────────────────────────────────────────────────────────
    // Sheet identification helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns true when the sheet belongs to "MBA Batch 17" (any trimester I–VI).
     */
    private fun isBatch17AnyTrimSheet(sheetName: String, sheet: Sheet): Boolean {
        val clean = sheetName.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), " ")
        val has17 = clean.contains("17") || clean.contains("B17") || clean.contains("BATCH 17")

        if (has17 && (clean.contains("TRIM") || clean.contains("TRI") || clean.contains("MBA"))) return true
        if (has17) return true

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
                    (txt.contains("TRIM") || txt.contains("TRI"))
                ) return true
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // Trimester detection
    // ─────────────────────────────────────────────────────────────

    /**
     * Detects which trimester (I–VI) is referenced in the sheet name or its first 10 rows.
     * Longer Roman numerals are checked before shorter ones to avoid mis-matches (VI before V, IV before I, etc.).
     */
    fun detectTrimesterLabel(sheetName: String, sheet: Sheet): String {
        val textParts = mutableListOf(sheetName)
        val maxRows = minOf(sheet.lastRowNum + 1, 10)
        for (r in 0 until maxRows) {
            val row = sheet.getRow(r) ?: continue
            for (c in 0 until minOf(row.lastCellNum.toInt().coerceAtLeast(0), 20)) {
                val txt = getCellValueAsString(row.getCell(c))
                if (txt.isNotBlank()) textParts.add(txt)
            }
        }
        val combined = textParts.joinToString(" ")
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9 ]"), " ")

        return when {
            Regex("TRIM\\s*VI|TRIMESTER\\s*VI|TRIM\\s*6|\\bT\\s*6\\b").containsMatchIn(combined) -> "Trim VI"
            Regex("TRIM\\s*IV|TRIMESTER\\s*IV|TRIM\\s*4|\\bT\\s*4\\b").containsMatchIn(combined) -> "Trim IV"
            Regex("TRIM\\s*V\\b|TRIMESTER\\s*V\\b|TRIM\\s*5|\\bT\\s*5\\b").containsMatchIn(combined) -> "Trim V"
            Regex("TRIM\\s*III|TRIMESTER\\s*III|TRIM\\s*3|\\bT\\s*3\\b").containsMatchIn(combined) -> "Trim III"
            Regex("TRIM\\s*II\\b|TRIMESTER\\s*II\\b|TRIM\\s*2|\\bT\\s*2\\b").containsMatchIn(combined) -> "Trim II"
            Regex("TRIM\\s*I\\b|TRIMESTER\\s*I\\b|TRIM\\s*1|\\bT\\s*1\\b").containsMatchIn(combined) -> "Trim I"
            else -> "Trim (Auto)"
        }
    }

    private fun hasScheduleHeaders(sheet: Sheet): Boolean {
        val rowIterator = sheet.rowIterator()
        var count = 0
        while (rowIterator.hasNext() && count < 25) {
            val row = rowIterator.next(); count++
            if (isHeaderRow(row)) return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // Sheet parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses one sheet.
     * @return Pair(filtered entries for [selectedDivision], all unique division letters found).
     */
    private fun parseSheet(
        sheet: Sheet,
        fileId: Int,
        selectedDivision: String = DEFAULT_DIVISION
    ): Pair<List<TimetableEntry>, Set<String>> {
        val entries = mutableListOf<TimetableEntry>()
        val seenDivisions = mutableSetOf<String>()
        val rowIterator = sheet.rowIterator()

        var headerRow: Row? = null
        var dayCol = -1; var dateCol = -1; var roomCol = -1; var divCol = -1
        var slot1Col = -1; var slot2Col = -1; var slot3Col = -1; var slot4Col = -1; var slot5Col = -1

        // Scan for header row
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            if (!isHeaderRow(row)) continue
            headerRow = row
            for (colIdx in 0 until row.lastCellNum) {
                val cellText = getCellValueAsString(row.getCell(colIdx)).trim().uppercase(Locale.US)
                if (cellText.isEmpty()) continue
                if (cellText.contains("MIN") || cellText.contains("BREAK") ||
                    cellText.contains("INTERVAL") || cellText.contains("LUNCH")) continue

                when {
                    cellText.contains("DAY") -> dayCol = colIdx
                    cellText.contains("DATE") || cellText.contains("DT") -> dateCol = colIdx
                    cellText.contains("ROOM") || cellText.contains("RM NO") ||
                        cellText.contains("RM.") || cellText.contains("CLASS") ||
                        cellText.contains("HALL") -> roomCol = colIdx
                    cellText.contains("DIV") || cellText.contains("DIVISION") ||
                        cellText.contains("SEC") || cellText.contains("SECTION") -> divCol = colIdx
                    cellText.contains("8:30") || cellText.contains("8.30") ||
                        cellText.contains("08:30") || cellText.contains("08.30") ||
                        (cellText.contains("SLOT") && cellText.contains("1")) -> slot1Col = colIdx
                    cellText.contains("10:15") || cellText.contains("10.15") ||
                        (cellText.contains("SLOT") && cellText.contains("2")) -> slot2Col = colIdx
                    cellText.contains("12:00") || cellText.contains("12.00") ||
                        cellText.contains("12-") || cellText.contains("12.0") ||
                        (cellText.contains("SLOT") && cellText.contains("3")) -> slot3Col = colIdx
                    cellText.contains("14:15") || cellText.contains("14.15") ||
                        cellText.contains("2:15") || cellText.contains("2.15") ||
                        (cellText.contains("SLOT") && cellText.contains("4")) -> slot4Col = colIdx
                    cellText.contains("16:00") || cellText.contains("16.00") ||
                        cellText.contains("4:00") || cellText.contains("4.00") ||
                        (cellText.contains("SLOT") && cellText.contains("5")) -> slot5Col = colIdx
                }
            }
            break
        }

        if (headerRow == null || dayCol == -1 || dateCol == -1 || divCol == -1) {
            Log.d(TAG, "Sheet '${sheet.sheetName}' skipped: Day/Date/Div columns not found.")
            return Pair(emptyList(), emptySet())
        }

        // Parse data rows
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val divVal = getCellValueAsString(row.getCell(divCol))
            val normalized = normalizeDiv(divVal)
            if (normalized.isNotEmpty()) seenDivisions.add(normalized)

            if (!matchesDivision(divVal, selectedDivision)) continue

            val dayVal = getCellValueAsString(row.getCell(dayCol)).trim()
            val dateCell = row.getCell(dateCol)
            val rawDateVal = getCellValueAsString(dateCell).trim()

            var normalizedDate: String? = null
            if (dateCell != null && dateCell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(dateCell)) {
                try { normalizedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dateCell.dateCellValue) } catch (_: Exception) { }
            }
            if (normalizedDate == null) normalizedDate = DateUtils.normalizeDate(rawDateVal)
            if (normalizedDate == null) continue

            val roomNo = (if (roomCol != -1) getCellValueAsString(row.getCell(roomCol)).trim() else "").ifEmpty { "TBA" }

            fun slot(col: Int) = if (col != -1) getCellValueAsString(row.getCell(col)).trim() else ""
            val finalSlot1 = slot(slot1Col).ifEmpty { "Free Slot" }
            val finalSlot2 = slot(slot2Col).ifEmpty { "Free Slot" }
            val finalSlot3 = slot(slot3Col).ifEmpty { "Free Slot" }
            val finalSlot4 = slot(slot4Col).ifEmpty { "Free Slot" }
            val finalSlot5 = slot(slot5Col).ifEmpty { "Free Slot" }

            if (dayVal.isEmpty() && finalSlot1 == "Free Slot" && finalSlot2 == "Free Slot" && finalSlot3 == "Free Slot") continue

            entries.add(
                TimetableEntry(
                    fileId = fileId,
                    day = if (dayVal.isEmpty()) DateUtils.getDayOfWeekName(normalizedDate) else dayVal,
                    date = normalizedDate,
                    roomNo = roomNo,
                    div = "Division ${selectedDivision.uppercase(Locale.US)}",
                    slot1 = finalSlot1,
                    slot2 = finalSlot2,
                    slot3 = finalSlot3,
                    slot4 = finalSlot4,
                    slot5 = finalSlot5
                )
            )
        }

        Log.d(TAG, "Sheet '${sheet.sheetName}': ${entries.size} entries for Division $selectedDivision")
        return Pair(entries, seenDivisions)
    }

    // ─────────────────────────────────────────────────────────────
    // Division helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Normalises a raw div cell value to a single uppercase letter, e.g. "Division A" → "A".
     * Returns empty string if the value cannot be resolved to a single letter.
     */
    private fun normalizeDiv(raw: String): String {
        val clean = raw.uppercase(Locale.US)
            .replace(Regex("DIVISION|DIV\\.?|SEC(?:TION)?"), "")
            .replace(Regex("[^A-Z0-9]"), "")
            .trim()
        return if (clean.length == 1 && clean[0].isLetter()) clean else ""
    }

    /**
     * Matches the division cell value against [targetDiv] (defaults to [DEFAULT_DIVISION]).
     * Restores the exact matching logic that worked reliably earlier.
     */
    private fun matchesDivision(divStr: String, targetDiv: String = DEFAULT_DIVISION): Boolean {
        val clean = divStr.trim().uppercase(Locale.US)
        val target = targetDiv.trim().uppercase(Locale.US)
        if (clean == target ||
            clean == "DIV $target" ||
            clean == "DIVISION $target" ||
            clean == "DIV-$target" ||
            clean == "DIV$target" ||
            clean.contains("DIV $target") ||
            clean.startsWith("DIV $target") ||
            clean.startsWith("DIV-$target") ||
            clean.startsWith("DIVISION $target") ||
            clean.endsWith(" $target") ||
            clean.contains("DIV. $target") ||
            clean.contains("SEC $target") ||
            clean.contains("SECTION $target") ||
            clean == "DIVISION-$target"
        ) {
            return true
        }
        val normalized = normalizeDiv(divStr)
        return normalized == target
    }

    /** Finds the 0-based column index of the Division column, or null if not found. */
    private fun findDivCol(sheet: Sheet): Int? {
        val rowIterator = sheet.rowIterator()
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            if (!isHeaderRow(row)) continue
            for (colIdx in 0 until row.lastCellNum) {
                val txt = getCellValueAsString(row.getCell(colIdx)).trim().uppercase(Locale.US)
                if (txt.contains("DIV") || txt.contains("DIVISION") ||
                    txt.contains("SEC") || txt.contains("SECTION")) return colIdx
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────
    // Row / cell utilities
    // ─────────────────────────────────────────────────────────────

    private fun isHeaderRow(row: Row): Boolean {
        var hasDay = false; var hasDate = false; var hasDivOrSlot = false
        for (colIdx in 0 until row.lastCellNum) {
            val v = getCellValueAsString(row.getCell(colIdx)).trim().uppercase(Locale.US)
            if (v.contains("DAY")) hasDay = true
            if (v.contains("DATE") || v.contains("DT")) hasDate = true
            if (v.contains("DIV") || v.contains("DIVISION") || v.contains("SEC") ||
                v.contains("8:30") || v.contains("8.30") || v.contains("SLOT")) hasDivOrSlot = true
        }
        return hasDay && hasDate && hasDivOrSlot
    }

    fun getCellValueAsString(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    try { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cell.dateCellValue) }
                    catch (_: Exception) { cell.numericCellValue.toString() }
                } else {
                    val num = cell.numericCellValue
                    if (num == num.toInt().toDouble()) num.toInt().toString() else num.toString()
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try { cell.stringCellValue } catch (_: Exception) {
                    try {
                        val eval = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                        val value = eval.evaluate(cell)
                        when (value.cellType) {
                            CellType.STRING -> value.stringValue
                            CellType.NUMERIC -> value.numberValue.toInt().toString()
                            else -> ""
                        }
                    } catch (_: Exception) { "" }
                }
            }
            else -> ""
        }
    }
}
