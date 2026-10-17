package com.chencohensolution.journal.stories

/**
 * A story's story.md: a `# Title` heading, the opening text, then one section per group, each starting with an
 * HTML comment marker that Markdown viewers hide:
 *
 * ```
 * # Japan trip
 *
 * We landed at night…
 *
 * <!-- group:g1 -->
 * Tuna auction at 5am.
 * ```
 */
internal data class StoryText(
    val intro: String,
    /** Group id → text, in file order. */
    val sections: Map<String, String>,
) {
    fun toMarkdown(title: String): String = buildString {
        append("# ").append(title.replace('\n', ' ')).append("\n")
        if (intro.isNotBlank()) append("\n").append(intro.trim()).append("\n")
        sections.forEach { (id, text) ->
            append("\n").append(marker(id)).append("\n")
            if (text.isNotBlank()) append(text.trim()).append("\n")
        }
    }

    companion object {
        private val MARKER = Regex("""^<!--\s*group:([A-Za-z0-9_-]+)\s*-->\s*$""")

        fun marker(id: String) = "<!-- group:$id -->"

        /** Reads [markdown]; the heading is dropped because the title lives in story.json. */
        fun parse(markdown: String): StoryText {
            val intro = StringBuilder()
            val sections = linkedMapOf<String, String>()
            var current: String? = null
            var section = StringBuilder()
            var headingSkipped = false
            markdown.lines().forEach { line ->
                val match = MARKER.find(line.trim())
                when {
                    match != null -> {
                        current?.let { sections[it] = section.toString().trim() }
                        current = match.groupValues[1]
                        section = StringBuilder()
                    }
                    current == null && !headingSkipped && line.startsWith("# ") -> headingSkipped = true
                    current == null -> intro.append(line).append('\n')
                    else -> section.append(line).append('\n')
                }
            }
            current?.let { sections[it] = section.toString().trim() }
            return StoryText(intro.toString().trim(), sections)
        }
    }
}
