/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package pl.klimonda.openhab.binding.psecompass.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

@NonNullByDefault
public final class PseCompassBindingConstants {
    public static final String BINDING_ID = "psecompass";
    public static final ThingTypeUID THING_TYPE_COMPASS = new ThingTypeUID(BINDING_ID, "compass");
    public static final String CONFIG_REFRESH_INTERVAL = "refreshInterval";
    public static final String CHANNEL_CURRENT_ZONE = "current-zone";
    public static final String CHANNEL_NEXT_ZONE = "next-zone";
    public static final String CHANNEL_NEXT_CHANGE = "next-change";
    public static final String CHANNEL_SCHEDULE = "schedule";
    public static final String CHANNEL_LAST_SUCCESSFUL_UPDATE = "last-successful-update";
    public static final String CHANNEL_ZONE_CHANGED = "zone-changed";

    private PseCompassBindingConstants() {
    }
}
