package com.marketplace.android.core.network

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.math.BigDecimal

/**
 * Reads decimal JSON tokens without converting them through Double.
 * Monetary values must preserve their exact decimal representation.
 */
internal class BigDecimalJsonAdapter : JsonAdapter<BigDecimal>() {
    override fun fromJson(reader: JsonReader): BigDecimal {
        return when (reader.peek()) {
            JsonReader.Token.NUMBER, JsonReader.Token.STRING -> {
                val raw = reader.nextString()
                try {
                    BigDecimal(raw)
                } catch (error: NumberFormatException) {
                    throw JsonDataException("Expected a decimal value at ${reader.path}", error)
                }
            }
            else -> throw JsonDataException("Expected a decimal value but was ${reader.peek()} at ${reader.path}")
        }
    }

    override fun toJson(writer: JsonWriter, value: BigDecimal?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(value)
        }
    }
}
