package com.paymentstraininghub.app.ui.theme

// ThumbSvg.kt
// A small, strict parser for the thumbnail SVG subset the website
// ships (assets/thumbs/*.svg): one 320x180 viewBox, and only rect,
// circle, path and text elements, in document order. Verified
// against all 1,608 shipped thumbnails on 16 Nov 2026: no other
// element or path command appears. Anything outside the subset is
// ignored element by element, and a document with no viewBox or
// no drawable elements yields null, so the caller falls back to
// its icon tile. Pure Kotlin, no Android types, so the parser is
// pinned by plain unit tests. The renderer lives in
// ThumbnailViews.kt and draws these shapes on a Compose Canvas.

/** A parsed fill or stroke paint: an RGB color, or none. */
sealed interface ThumbPaint {
    data object None : ThumbPaint
    data class Color(val red: Int, val green: Int, val blue: Int) : ThumbPaint
}

/** One drawable shape from a thumbnail SVG, in document order. */
sealed interface ThumbShape {
    val opacity: Float

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val cornerRadius: Float,
        val fill: ThumbPaint,
        override val opacity: Float
    ) : ThumbShape

    data class Circle(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val fill: ThumbPaint,
        val stroke: ThumbPaint,
        val strokeWidth: Float,
        override val opacity: Float
    ) : ThumbShape

    data class Path(
        val commands: List<PathCommand>,
        val fill: ThumbPaint,
        val stroke: ThumbPaint,
        val strokeWidth: Float,
        val roundCaps: Boolean,
        override val opacity: Float
    ) : ThumbShape

    data class Label(
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val bold: Boolean,
        val fill: ThumbPaint,
        val text: String,
        override val opacity: Float
    ) : ThumbShape
}

/** One path command: M, L, H, V (absolute), l, h, v (relative), or Z. */
sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data object Close : PathCommand
}

/** A parsed thumbnail: its viewBox size and its shapes in paint order. */
data class ThumbSvg(
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    val shapes: List<ThumbShape>
)

/**
 * Parse thumbnail SVG text. Returns null when the document has no
 * usable viewBox, no drawable shapes, or is not SVG at all. Never
 * throws: every malformed fragment is skipped, never fatal.
 */
fun parseThumbSvg(svgText: String): ThumbSvg? {
    return runCatching { parseThumbSvgOrThrow(svgText) }.getOrNull()
}

private fun parseThumbSvgOrThrow(svgText: String): ThumbSvg? {
    if (!svgText.contains("<svg", ignoreCase = true)) return null
    val svgTag = Regex("<svg[^>]*>", RegexOption.IGNORE_CASE).find(svgText)?.value ?: return null
    val attrs = parseAttributes(svgTag)
    // Attribute names are lowercased by parseAttributes, so the
    // camelCase viewBox arrives as "viewbox".
    val viewBox = attrs["viewbox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
    val viewBoxWidth: Float
    val viewBoxHeight: Float
    if (viewBox != null && viewBox.size == 4 && viewBox[2] > 0f && viewBox[3] > 0f) {
        viewBoxWidth = viewBox[2]
        viewBoxHeight = viewBox[3]
    } else {
        viewBoxWidth = attrs["width"]?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        viewBoxHeight = attrs["height"]?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
    }
    val shapes = mutableListOf<ThumbShape>()
    // Elements in document order: self-closing rect/circle/path,
    // and text elements with content. Title and svg tags are
    // skipped by the tag-name match.
    val elementPattern = Regex(
        "<(rect|circle|path)\\b([^>]*?)/?>|<text\\b([^>]*)>(.*?)</text>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    for (match in elementPattern.findAll(svgText)) {
        val shapeTag = match.groups[1]?.value?.lowercase()
        if (shapeTag != null) {
            val tagAttrs = parseAttributes(match.groups[2]?.value.orEmpty())
            parseShape(shapeTag, tagAttrs)?.let { shapes.add(it) }
        } else {
            val tagAttrs = parseAttributes(match.groups[3]?.value.orEmpty())
            val rawText = match.groups[4]?.value.orEmpty()
            val text = decodeEntities(rawText.replace(Regex("<[^>]*>"), "")).trim()
            if (text.isNotEmpty()) {
                parseLabel(tagAttrs, text)?.let { shapes.add(it) }
            }
        }
    }
    if (shapes.isEmpty()) return null
    return ThumbSvg(viewBoxWidth, viewBoxHeight, shapes)
}

private fun parseShape(tag: String, attrs: Map<String, String>): ThumbShape? {
    val opacity = attrs["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    return when (tag) {
        "rect" -> {
            val width = attrs["width"]?.toFloatOrNull() ?: return null
            val height = attrs["height"]?.toFloatOrNull() ?: return null
            if (width <= 0f || height <= 0f) return null
            ThumbShape.Rect(
                x = attrs["x"]?.toFloatOrNull() ?: 0f,
                y = attrs["y"]?.toFloatOrNull() ?: 0f,
                width = width,
                height = height,
                cornerRadius = attrs["rx"]?.toFloatOrNull() ?: 0f,
                fill = parsePaint(attrs["fill"]),
                opacity = opacity
            )
        }
        "circle" -> {
            val r = attrs["r"]?.toFloatOrNull() ?: return null
            if (r <= 0f) return null
            ThumbShape.Circle(
                cx = attrs["cx"]?.toFloatOrNull() ?: 0f,
                cy = attrs["cy"]?.toFloatOrNull() ?: 0f,
                r = r,
                fill = parsePaint(attrs["fill"]),
                stroke = parsePaint(attrs["stroke"]),
                strokeWidth = attrs["stroke-width"]?.toFloatOrNull() ?: 0f,
                opacity = opacity
            )
        }
        "path" -> {
            val d = attrs["d"] ?: return null
            val commands = parsePathData(d)
            if (commands.isEmpty()) return null
            ThumbShape.Path(
                commands = commands,
                fill = parsePaint(attrs["fill"]),
                stroke = parsePaint(attrs["stroke"]),
                strokeWidth = attrs["stroke-width"]?.toFloatOrNull() ?: 0f,
                roundCaps = attrs["stroke-linecap"]?.equals("round", ignoreCase = true) == true,
                opacity = opacity
            )
        }
        else -> null
    }
}

private fun parseLabel(attrs: Map<String, String>, text: String): ThumbShape.Label? {
    val fontSize = attrs["font-size"]?.toFloatOrNull() ?: return null
    if (fontSize <= 0f) return null
    val weight = attrs["font-weight"]?.trim()?.lowercase()
    return ThumbShape.Label(
        x = attrs["x"]?.toFloatOrNull() ?: 0f,
        y = attrs["y"]?.toFloatOrNull() ?: 0f,
        fontSize = fontSize,
        bold = weight == "bold" || (weight?.toIntOrNull() ?: 0) >= 600,
        fill = parsePaint(attrs["fill"]),
        text = text,
        opacity = attrs["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    )
}

/** Parse a paint value: #rgb, #rrggbb, or none. Unknown values yield None. */
internal fun parsePaint(raw: String?): ThumbPaint {
    val value = raw?.trim()?.lowercase() ?: return ThumbPaint.None
    if (value == "none" || value == "transparent") return ThumbPaint.None
    if (!value.startsWith("#")) return ThumbPaint.None
    val hex = value.removePrefix("#")
    return when (hex.length) {
        3 -> {
            val r = hex[0].digitToIntOrNull(16) ?: return ThumbPaint.None
            val g = hex[1].digitToIntOrNull(16) ?: return ThumbPaint.None
            val b = hex[2].digitToIntOrNull(16) ?: return ThumbPaint.None
            ThumbPaint.Color(r * 17, g * 17, b * 17)
        }
        6 -> {
            val r = hex.substring(0, 2).toIntOrNull(16) ?: return ThumbPaint.None
            val g = hex.substring(2, 4).toIntOrNull(16) ?: return ThumbPaint.None
            val b = hex.substring(4, 6).toIntOrNull(16) ?: return ThumbPaint.None
            ThumbPaint.Color(r, g, b)
        }
        else -> ThumbPaint.None
    }
}

/**
 * Parse path data with M, L, H, V, l, h, v and Z commands. Command
 * letters may repeat coordinate pairs (M 1 2 3 4 means move then
 * line), and numbers may run together with signs. Anything else
 * stops the parse at that command; the commands parsed so far are
 * kept. All coordinates resolve to absolute move/line commands.
 */
internal fun parsePathData(d: String): List<PathCommand> {
    val tokens = Regex("[MmLlHhVvZz]|-?\\d*\\.?\\d+(?:[eE][+-]?\\d+)?")
        .findAll(d)
        .map { it.value }
        .toList()
    val commands = mutableListOf<PathCommand>()
    var i = 0
    var command: Char? = null
    var cx = 0f
    var cy = 0f
    var startX = 0f
    var startY = 0f
    var nextPairIsMove = false
    fun number(): Float? {
        if (i >= tokens.size) return null
        return tokens[i].toFloatOrNull()?.also { i++ }
    }
    while (i < tokens.size) {
        val token = tokens[i]
        if (token.length == 1 && token[0].isLetter()) {
            command = token[0]
            i++
            if (command == 'Z' || command == 'z') {
                commands.add(PathCommand.Close)
                cx = startX
                cy = startY
                command = null
            } else if (command == 'M' || command == 'm') {
                // The first pair after M is a move; later pairs
                // under the same M are implicit lines.
                nextPairIsMove = true
            }
            continue
        }
        when (command) {
            'M', 'm', 'L', 'l' -> {
                val first = number() ?: break
                val second = number() ?: break
                val absolute = command == 'M' || command == 'L'
                val x = if (absolute) first else cx + first
                val y = if (absolute) second else cy + second
                if (nextPairIsMove) {
                    commands.add(PathCommand.MoveTo(x, y))
                    startX = x
                    startY = y
                    nextPairIsMove = false
                    command = if (command == 'M') 'L' else 'l'
                } else {
                    commands.add(PathCommand.LineTo(x, y))
                }
                cx = x
                cy = y
            }
            'H', 'h' -> {
                val value = number() ?: break
                val x = if (command == 'H') value else cx + value
                commands.add(PathCommand.LineTo(x, cy))
                cx = x
            }
            'V', 'v' -> {
                val value = number() ?: break
                val y = if (command == 'V') value else cy + value
                commands.add(PathCommand.LineTo(cx, y))
                cy = y
            }
            else -> break
        }
    }
    return commands
}

/** Attribute map for one tag fragment: name="value" pairs, lowercased names. */
private fun parseAttributes(fragment: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val pattern = Regex("([A-Za-z_:][A-Za-z0-9_:.\\-]*)\\s*=\\s*\"([^\"]*)\"")
    for (match in pattern.findAll(fragment)) {
        result[match.groupValues[1].lowercase()] = decodeEntities(match.groupValues[2])
    }
    return result
}

/** Decode the XML entities that appear in thumbnail text and attributes. */
private fun decodeEntities(raw: String): String {
    return raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
