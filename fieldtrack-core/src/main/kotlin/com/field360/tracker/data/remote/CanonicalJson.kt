package com.field360.tracker.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Rebuilds the exact bytes the server signed: every key except `signature`, sorted
 * lexicographically, no whitespace, numbers rendered as they arrived.
 *
 * The trap this exists to avoid is `gson.fromJson(raw, Map::class.java)`, which parses
 * every JSON number into a `Double`. Re-serialise and `86400` becomes `86400.0`, the
 * bytes stop matching, and **every** signature check fails — silently, because the check
 * fails open. [JsonParser] keeps each number as a `LazilyParsedNumber` that still holds
 * the original text, so `asString` gives back `86400` exactly.
 *
 * Returns null rather than throwing on anything unexpected. A nested object or array in
 * the signed set would mean the server contract moved underneath us, and the caller's
 * contract is to treat that as a failed check, never as a crash on a background thread.
 */
internal fun canonicalize(raw: String): String? = runCatching {
    val json = JsonParser.parseString(raw).asJsonObject
    val keys = json.keySet()
        .filter { it != SIGNATURE_FIELD }
        .sorted()

    buildString {
        append('{')
        keys.forEachIndexed { index, key ->
            if (index > 0) append(',')
            append(canonicalGson.toJson(JsonPrimitive(key)))
            append(':')
            append(encodeCanonical(json.get(key)) ?: return@runCatching null)
        }
        append('}')
    }
}.getOrNull()

internal const val SIGNATURE_FIELD: String = "signature"

/**
 * `disableHtmlEscaping` is not cosmetic here: by default Gson writes `<` as the escape
 * sequence `\u003c`, and does the same for `>`, `=` and `&` — a different byte sequence
 * from the one the server signed.
 */
private val canonicalGson = GsonBuilder().disableHtmlEscaping().create()

/** Null for anything the signed payload has never contained — see [canonicalize]. */
private fun encodeCanonical(value: JsonElement?): String? = when {
    value == null -> null
    value.isJsonNull -> "null"
    value !is JsonPrimitive -> null
    value.isString -> canonicalGson.toJson(value)
    value.isBoolean -> value.asString
    value.isNumber -> value.asString
    else -> null
}
