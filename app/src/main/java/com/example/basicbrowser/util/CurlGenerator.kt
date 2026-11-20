package com.example.basicbrowser.util

/**
 * Pure JVM-testable utility to generate bash-compatible cURL commands.
 */
object CurlGenerator {

    /**
     * Generates a bash-compatible cURL command string for an HTTP request.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param url Full target URL
     * @param headers List of header strings in "Key: Value" or HTTP request line format
     * @param body Optional request body bytes
     */
    fun generateCurl(
        method: String,
        url: String,
        headers: List<String>,
        body: ByteArray?
    ): String {
        val sb = StringBuilder()
        sb.append("curl -i -s -k -X ").append(escapeBash(method.uppercase()))

        for (header in headers) {
            val colonIndex = header.indexOf(':')
            if (colonIndex <= 0) continue // Skip request-line or invalid headers

            val key = header.substring(0, colonIndex).trim()
            // Skip hop-by-hop or auto-generated headers that curl generates itself
            if (key.equals("Content-Length", ignoreCase = true)) continue

            sb.append(" \\\n  -H ").append(escapeBash(header.trim()))
        }

        if (body != null && body.isNotEmpty()) {
            val bodyString = try {
                String(body, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            if (bodyString.isNotEmpty()) {
                sb.append(" \\\n  --data-binary ").append(escapeBash(bodyString))
            }
        }

        sb.append(" \\\n  ").append(escapeBash(url))
        return sb.toString()
    }

    /**
     * Escapes a string safely for bash single-quote argument wrapping:
     * 'val' -> 'val'
     * 'don't' -> 'don'\''t'
     */
    fun escapeBash(input: String): String {
        return "'" + input.replace("'", "'\\''") + "'"
    }
}

