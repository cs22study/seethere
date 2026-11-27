package com.cs22study.seethere.core

/**
 * Pure Kotlin RFC 4180 CSV & JSON codec for camera import/export with security validation and soft-delete fields.
 */
object CameraCodec {

    private const val CSV_HEADER =
        "id,title,region,city,category,stream_url,thumbnail_url,latitude,longitude,source_name,is_favorite,sort_order,is_deleted,deleted_at"

    fun exportToCsv(cameras: List<CameraItem>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        for (c in cameras) {
            val cells = listOf(
                StreamSecuritySanitizer.escapeCsvCell(c.id),
                StreamSecuritySanitizer.escapeCsvCell(c.title),
                StreamSecuritySanitizer.escapeCsvCell(c.region),
                StreamSecuritySanitizer.escapeCsvCell(c.city),
                StreamSecuritySanitizer.escapeCsvCell(c.category),
                StreamSecuritySanitizer.escapeCsvCell(c.streamUrl),
                StreamSecuritySanitizer.escapeCsvCell(c.thumbnailUrl),
                c.latitude.toString(),
                c.longitude.toString(),
                StreamSecuritySanitizer.escapeCsvCell(c.sourceName),
                c.isFavorite.toString(),
                c.sortOrder.toString(),
                c.isDeleted.toString(),
                StreamSecuritySanitizer.escapeCsvCell(c.deletedAtIso ?: ""),
            )
            sb.append(cells.joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }

    fun importFromCsv(
        csvContent: String,
        allowPrivateLanHttp: Boolean = false,
    ): CameraImportReport {
        val cleanContent = csvContent.removePrefix("\uFEFF")
        val rows = parseCsvRows(cleanContent)
        if (rows.isEmpty()) return CameraImportReport(emptyList(), emptyList())

        val firstRow = rows.first()
        val hasHeader = firstRow.any { it.trim().equals("stream_url", ignoreCase = true) || it.trim().equals("title", ignoreCase = true) }
        val dataRows = if (hasHeader) rows.drop(1) else rows
        val startLine = if (hasHeader) 2 else 1

        val accepted = ArrayList<CameraItem>()
        val rejected = ArrayList<ImportRejection>()

        dataRows.forEachIndexed { idx, cols ->
            val lineNo = startLine + idx
            if (cols.all { it.isBlank() }) return@forEachIndexed

            if (cols.size < 9) {
                rejected.add(ImportRejection(lineNo, cols.getOrNull(1).orEmpty(), "欄位數量不足 (至少需 9 欄)"))
                return@forEachIndexed
            }

            val rawId = StreamSecuritySanitizer.unescapeImportedText(cols[0]).ifBlank { "imp_$lineNo" }
            val title = StreamSecuritySanitizer.unescapeImportedText(cols[1])
            val region = StreamSecuritySanitizer.unescapeImportedText(cols[2])
            val city = StreamSecuritySanitizer.unescapeImportedText(cols[3])
            val category = StreamSecuritySanitizer.unescapeImportedText(cols[4]).ifBlank { "自訂" }
            val rawStreamUrl = cols[5].trim()
            val rawThumbUrl = cols[6].trim()
            val lat = cols[7].trim().toDoubleOrNull()
            val lon = cols[8].trim().toDoubleOrNull()

            if (title.isBlank()) {
                rejected.add(ImportRejection(lineNo, rawId, "攝影機名稱不可為空白"))
                return@forEachIndexed
            }
            if (lat == null || lon == null || !GeoSpatialEngine.isValidCoordinate(lat, lon)) {
                rejected.add(ImportRejection(lineNo, title, "經緯度座標格式或範圍無效"))
                return@forEachIndexed
            }

            val urlCheck = StreamSecuritySanitizer.sanitizeStreamUrl(rawStreamUrl, allowPrivateLanHttp)
            if (!urlCheck.isAllowed) {
                rejected.add(ImportRejection(lineNo, title, urlCheck.blockReason ?: "串流網址未通過資安檢查"))
                return@forEachIndexed
            }

            val safeThumb = if (rawThumbUrl.isBlank()) {
                if (urlCheck.extractedYouTubeId != null) {
                    "https://i.ytimg.com/vi/${urlCheck.extractedYouTubeId}/hqdefault.jpg"
                } else {
                    ""
                }
            } else {
                val thumbCheck = StreamSecuritySanitizer.sanitizeStreamUrl(rawThumbUrl, allowPrivateLanHttp)
                if (thumbCheck.isAllowed) thumbCheck.normalizedUrl else ""
            }

            val sourceName = StreamSecuritySanitizer.unescapeImportedText(cols.getOrElse(9) { "" })
            val isFavorite = cols.getOrElse(10) { "false" }.trim().equals("true", ignoreCase = true)
            val sortOrder = cols.getOrElse(11) { "0" }.trim().toIntOrNull() ?: 0
            val isDeleted = cols.getOrElse(12) { "false" }.trim().equals("true", ignoreCase = true)
            val deletedAt = StreamSecuritySanitizer.unescapeImportedText(cols.getOrElse(13) { "" }).ifBlank { null }

            accepted.add(
                CameraItem(
                    id = rawId,
                    title = title,
                    region = region,
                    city = city,
                    category = category,
                    streamUrl = urlCheck.normalizedUrl,
                    thumbnailUrl = safeThumb,
                    latitude = lat,
                    longitude = lon,
                    sourceName = sourceName,
                    isFavorite = isFavorite,
                    sortOrder = sortOrder,
                    isDeleted = isDeleted,
                    deletedAtIso = deletedAt,
                    allowLocalHttp = allowPrivateLanHttp,
                )
            )
        }

        return CameraImportReport(accepted = accepted, rejected = rejected)
    }

    /**
     * Parses RFC 4180 CSV content supporting quoted commas and escaped double quotes.
     */
    internal fun parseCsvRows(input: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var currentRow = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        cell.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(cell.toString())
                        cell.clear()
                    }
                    '\r' -> {
                        if (i + 1 < input.length && input[i + 1] == '\n') i++
                        currentRow.add(cell.toString())
                        cell.clear()
                        rows.add(currentRow)
                        currentRow = ArrayList()
                    }
                    '\n' -> {
                        currentRow.add(cell.toString())
                        cell.clear()
                        rows.add(currentRow)
                        currentRow = ArrayList()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(cell.toString())
            rows.add(currentRow)
        }
        return rows
    }
}
