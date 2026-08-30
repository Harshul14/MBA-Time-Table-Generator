package com.example.utils

import android.content.Context
import android.graphics.*
import com.example.data.local.TimetableEntry
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale

object PngRenderer {

    /**
     * Generates a high-quality weekly timetable PNG image and saves it to internal storage.
     * @return Absolute file path of the saved PNG image.
     */
    fun renderWeeklyTimetable(
        context: Context,
        weekStartDate: LocalDate,
        entries: List<TimetableEntry>
    ): String {
        // 1. Dimensions
        val width = 1600
        val height = 1100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 2. Colors & Styling Palette
        val colorBg = 0xFFF8FAFC.toInt()          // Slate 50 (Very light gray/blue)
        val colorCard = 0xFFFFFFFF.toInt()        // White
        val colorPrimary = 0xFF0F172A.toInt()     // Slate 900 (Deep charcoal)
        val colorAccent = 0xFF4F46E5.toInt()      // Indigo 600 (Core branding accent)
        val colorTextMain = 0xFF1E293B.toInt()    // Slate 800 (Dark gray text)
        val colorTextSecondary = 0xFF64748B.toInt() // Slate 500 (Medium gray)
        val colorGridLine = 0xFFE2E8F0.toInt()    // Slate 200 (Subtle borders)
        val colorFreeSlotBg = 0xFFF1F5F9.toInt()  // Slate 100 (Subtle background for free slots)
        val colorFreeSlotText = 0xFF94A3B8.toInt()// Slate 400 (Light gray text)
        val colorHeaderBg = 0xFF1E293B.toInt()    // Slate 800 for the table headers
        
        // 3. Draw Background
        canvas.drawColor(colorBg)

        // 4. Draw Header Card
        val headerCardPaint = Paint().apply {
            color = colorCard
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val headerCardRect = RectF(40f, 40f, width - 40f, 200f)
        canvas.drawRoundRect(headerCardRect, 16f, 16f, headerCardPaint)

        // Draw Header Text
        val titlePaint = Paint().apply {
            color = colorPrimary
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText("MBA WEEKLY TIMETABLE", 80f, 110f, titlePaint)

        val subTitlePaint = Paint().apply {
            color = colorAccent
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText("DIVISION A", 80f, 155f, subTitlePaint)

        // Draw Week Range Text (Aligned Right)
        val weekRangeStr = "Week: ${DateUtils.formatWeekRange(weekStartDate, weekStartDate.plusDays(6))}"
        val weekPaint = Paint().apply {
            color = colorTextSecondary
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(weekRangeStr, width - 80f, 135f, weekPaint)

        // 5. Draw Table Grid Card
        val tableCardRect = RectF(40f, 230f, width - 40f, height - 40f)
        canvas.drawRoundRect(tableCardRect, 16f, 16f, headerCardPaint)

        // Columns definition
        val colCount = 6
        val colWidths = FloatArray(colCount)
        colWidths[0] = 220f // Day & Date
        val remainingWidth = (width - 80f - colWidths[0])
        val slotWidth = remainingWidth / 5
        for (i in 1 until colCount) {
            colWidths[i] = slotWidth
        }

        // Column X coordinates
        val colX = FloatArray(colCount + 1)
        colX[0] = 40f
        for (i in 0 until colCount) {
            colX[i + 1] = colX[i] + colWidths[i]
        }

        // Rows definition (Header + Monday to Saturday)
        val rowCount = 7
        val rowHeight = (height - 230f - 40f) / rowCount
        val rowY = FloatArray(rowCount + 1)
        rowY[0] = 230f
        for (i in 0 until rowCount) {
            rowY[i + 1] = rowY[i] + rowHeight
        }

        // 6. Draw Table Header Row
        val headerBgPaint = Paint().apply {
            color = colorHeaderBg
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        // Draw header background (rounded top corners manually or simply clip)
        val headerRect = RectF(40f, 230f, width - 40f, rowY[1])
        canvas.drawRect(headerRect, headerBgPaint)

        // Draw Header Text
        val headerTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val headerTitles = listOf(
            "DAY / DATE",
            "8:30 - 10:00\n(Slot 1)",
            "10:15 - 11:45\n(Slot 2)",
            "12:00 - 13:30\n(Slot 3)",
            "14:15 - 15:45\n(Slot 4)",
            "16:00 - 17:30\n(Slot 5)"
        )

        for (i in 0 until colCount) {
            val centerX = colX[i] + colWidths[i] / 2f
            val titleLines = headerTitles[i].split("\n")
            if (titleLines.size == 1) {
                canvas.drawText(titleLines[0], centerX, rowY[0] + rowHeight / 2f + 8f, headerTextPaint)
            } else {
                canvas.drawText(titleLines[0], centerX, rowY[0] + rowHeight / 2f - 4f, headerTextPaint)
                canvas.drawText(titleLines[1], centerX, rowY[0] + rowHeight / 2f + 20f, headerTextPaint)
            }
        }

        // Map entries by DayOfWeek (Monday = 1, ..., Sunday = 7) for fast access
        val daysOfWeekList = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

        // 7. Draw Day Rows
        val dayNamePaint = Paint().apply {
            color = colorPrimary
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val dayDatePaint = Paint().apply {
            color = colorAccent
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val dayRoomPaint = Paint().apply {
            color = colorTextSecondary
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val cellSubjectPaint = Paint().apply {
            color = colorTextMain
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val cellFreePaint = Paint().apply {
            color = colorFreeSlotText
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val cellRoomPaint = Paint().apply {
            color = colorTextSecondary
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val gridLinePaint = Paint().apply {
            color = colorGridLine
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val freeSlotBgPaint = Paint().apply {
            color = colorFreeSlotBg
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (rowIndex in 1..6) { // 1 to 6 matches Monday to Saturday
            val dayName = daysOfWeekList[rowIndex - 1]
            val rowYStart = rowY[rowIndex]
            val rowYEnd = rowY[rowIndex + 1]

            // Find entry for this day in the list
            val entryDate = weekStartDate.plusDays((rowIndex - 1).toLong())
            val entryDateStr = DateUtils.formatDateToStandard(entryDate)
            val dayEntry = entries.find { it.date == entryDateStr }

            // A. Draw Day / Date Cell
            val centerX = colX[0] + colWidths[0] / 2f
            canvas.drawText(dayName, centerX, rowYStart + 45f, dayNamePaint)
            
            val formattedDateStr = entryDate.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM", Locale.US))
            canvas.drawText(formattedDateStr, centerX, rowYStart + 80f, dayDatePaint)

            val roomStr = dayEntry?.roomNo ?: "TBA"
            canvas.drawText(roomStr, centerX, rowYStart + 115f, dayRoomPaint)

            // B. Draw Time Slots (Slots 1 to 5)
            val slots = listOf(
                dayEntry?.slot1 ?: "Free Slot",
                dayEntry?.slot2 ?: "Free Slot",
                dayEntry?.slot3 ?: "Free Slot",
                dayEntry?.slot4 ?: "Free Slot",
                dayEntry?.slot5 ?: "Free Slot"
            )

            for (slotIndex in 0..4) {
                val colIndex = slotIndex + 1
                val cellXStart = colX[colIndex]
                val cellXEnd = colX[colIndex + 1]
                val cellCenterX = cellXStart + colWidths[colIndex] / 2f
                val subject = slots[slotIndex]

                if (subject.uppercase(Locale.US) == "FREE SLOT" || subject.isEmpty()) {
                    // Draw Free Slot Background
                    val freeRect = RectF(cellXStart + 4f, rowYStart + 4f, cellXEnd - 4f, rowYEnd - 4f)
                    canvas.drawRoundRect(freeRect, 8f, 8f, freeSlotBgPaint)
                    canvas.drawText("Free Slot", cellCenterX, rowYStart + (rowHeight / 2f) + 6f, cellFreePaint)
                } else {
                    // Draw Subject with Wrapped Text
                    val textPadding = 15f
                    val maxTextWidth = colWidths[colIndex] - textPadding * 2
                    
                    // Determine room number to draw at bottom
                    val subRoomNo = dayEntry?.roomNo ?: "TBA"

                    // Draw Subject wrapped
                    val startY = rowYStart + 40f
                    val finalY = drawWrappedTextCentered(
                        canvas = canvas,
                        text = subject,
                        centerX = cellCenterX,
                        startY = startY,
                        width = maxTextWidth,
                        paint = cellSubjectPaint,
                        lineHeight = 26f
                    )

                    // Draw small Room details if space permits
                    canvas.drawText("Room: $subRoomNo", cellCenterX, rowYEnd - 25f, cellRoomPaint)
                }
            }
        }

        // 8. Draw Grid Lines
        // Horizontal Lines
        for (i in 0..rowCount) {
            canvas.drawLine(40f, rowY[i], width - 40f, rowY[i], gridLinePaint)
        }
        // Vertical Lines
        for (i in 0..colCount) {
            canvas.drawLine(colX[i], 230f, colX[i], height - 40f, gridLinePaint)
        }

        // Outer border for aesthetic crispness
        val borderPaint = Paint().apply {
            color = colorGridLine
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val outerRect = RectF(40f, 230f, width - 40f, height - 40f)
        canvas.drawRoundRect(outerRect, 16f, 16f, borderPaint)

        // 9. Save Bitmap to PNG
        val generatedDir = File(context.filesDir, "generated_timetables")
        if (!generatedDir.exists()) {
            generatedDir.mkdirs()
        }

        val pngFile = File(generatedDir, "timetable_${weekStartDate}.png")
        try {
            val out = FileOutputStream(pngFile)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return pngFile.absolutePath
    }

    /**
     * Center-aligned text wrap helper. Draws line by line.
     * @return The Y coordinate of the next available drawing line.
     */
    private fun drawWrappedTextCentered(
        canvas: Canvas,
        text: String,
        centerX: Float,
        startY: Float,
        width: Float,
        paint: Paint,
        lineHeight: Float
    ): Float {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            val measure = paint.measureText(testLine)
            if (measure > width) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    lines.add(word)
                }
            } else {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        var currentY = startY
        for (line in lines) {
            canvas.drawText(line, centerX, currentY, paint)
            currentY += lineHeight
        }
        return currentY
    }
}
