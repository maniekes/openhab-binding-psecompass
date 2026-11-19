/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal.api;

import java.time.Instant;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

/** Only the fields consumed from PSE's pdgsz report, with explicitly UTC instants. */
public record PseRow(@SerializedName("dtime_utc") @JsonAdapter(PseUtcTimestampAdapter.class) Instant start,
        @SerializedName("business_date") String businessDate,
        @SerializedName("publication_ts_utc") @JsonAdapter(PseUtcTimestampAdapter.class) Instant publishedAt,
        @SerializedName("usage_fcst") Integer usageForecast,
        @SerializedName("is_active") Boolean active) {
}
