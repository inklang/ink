package org.inklang.peg

/**
 * Represents a parsed package manifest.toml file.
 */
data class PackageManifest(
    val name: String,
    val version: String,
    val inkVersion: String,
    val description: String = "",
    val dependencies: List<Pair<String, String>> = emptyList(),
    val visibility: Visibility = Visibility()
) {
    data class Visibility(
        val pubScript: List<String> = emptyList(),
        val pub: List<String> = emptyList()
    )

    /**
     * Validates that required fields are non-empty.
     * @throws IllegalArgumentException if name, version, or inkVersion is blank
     */
    private fun validate(): PackageManifest {
        require(name.isNotBlank()) { "Manifest 'name' must not be blank" }
        require(version.isNotBlank()) { "Manifest 'version' must not be blank" }
        require(inkVersion.isNotBlank()) { "Manifest 'ink_version' must not be blank" }
        return this
    }

    companion object {
        /**
         * Parse a TOML manifest string into a PackageManifest.
         * Only implements what's needed for manifest.toml:
         * - [package] section: name, version, ink_version, description
         * - [dependencies] section: key = "value" pairs
         * - [visibility] section: pub_script and pub arrays
         */
        fun parse(input: String): PackageManifest {
            val lines = input.lines()
            var currentSection: String? = null

            var name: String = ""
            var version: String = ""
            var inkVersion: String = ""
            var description: String = ""
            val dependencies = mutableListOf<Pair<String, String>>()
            var pubScript = emptyList<String>()
            var pub = emptyList<String>()

            for (line in lines) {
                val trimmed = line.trim()

                // Skip empty lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                // Check for section header
                if (trimmed.startsWith("[")) {
                    val sectionEnd = trimmed.lastIndexOf(']')
                    if (sectionEnd > 0) {
                        currentSection = trimmed.substring(1, sectionEnd).trim()
                    }
                    continue
                }

                // Parse key = value pairs
                val equalsIndex = trimmed.indexOf('=')
                if (equalsIndex > 0) {
                    val key = trimmed.substring(0, equalsIndex).trim()
                    val value = trimmed.substring(equalsIndex + 1).trim()

                    when (currentSection) {
                        "package" -> {
                            when (key) {
                                "name" -> name = parseStringValue(value)
                                "version" -> version = parseStringValue(value)
                                "ink_version" -> inkVersion = parseStringValue(value)
                                "description" -> description = parseStringValue(value)
                            }
                        }
                        "dependencies" -> {
                            dependencies.add(key to parseStringValue(value))
                        }
                        "visibility" -> {
                            when (key) {
                                "pub_script" -> pubScript = parseArrayValue(value)
                                "pub" -> pub = parseArrayValue(value)
                            }
                        }
                    }
                }
            }

            return PackageManifest(
                name = name,
                version = version,
                inkVersion = inkVersion,
                description = description,
                dependencies = dependencies,
                visibility = Visibility(pubScript, pub)
            ).validate()
        }

        private fun parseStringValue(value: String): String {
            // Remove surrounding quotes if present
            return if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value.substring(1, value.length - 1)
            } else {
                value
            }
        }

        private fun parseArrayValue(value: String): List<String> {
            val content = value.trim()
            if (!content.startsWith("[") || !content.endsWith("]")) {
                return emptyList()
            }

            val arrayContent = content.substring(1, content.length - 1).trim()
            if (arrayContent.isEmpty()) {
                return emptyList()
            }

            return arrayContent
                .split(",")
                .map { parseStringValue(it.trim()) }
                .filter { it.isNotEmpty() }
        }
    }
}
