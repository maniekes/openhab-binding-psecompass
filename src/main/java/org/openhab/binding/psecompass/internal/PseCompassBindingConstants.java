/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal;

import org.openhab.core.thing.ThingTypeUID;

public final class PseCompassBindingConstants {
    public static final String BINDING_ID = "psecompass";
    public static final ThingTypeUID THING_TYPE_COMPASS = new ThingTypeUID(BINDING_ID, "compass");

    private PseCompassBindingConstants() {
    }
}
