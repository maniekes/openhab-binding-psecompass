/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package pl.klimonda.openhab.binding.psecompass.internal.api;

import java.util.List;

/** PSE report envelope; pagination must never be silently ignored. */
record PseResponse(List<PseRow> value, String nextLink) {
}
