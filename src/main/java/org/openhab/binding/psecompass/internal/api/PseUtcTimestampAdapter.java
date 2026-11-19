/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal.api;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;

/** PSE *_utc fields omit their offset; never interpret them in the host's timezone. */
public final class PseUtcTimestampAdapter implements JsonDeserializer<Instant> {
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter().withResolverStyle(java.time.format.ResolverStyle.STRICT);

    @Override
    public Instant deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
        if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
            throw new com.google.gson.JsonParseException("Expected a PSE UTC timestamp string");
        }
        return LocalDateTime.parse(json.getAsString(), FORMAT).toInstant(ZoneOffset.UTC);
    }
}
