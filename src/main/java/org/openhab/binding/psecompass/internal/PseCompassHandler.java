/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal;

import org.openhab.binding.psecompass.internal.api.PseCompassClient;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;

/** Binding skeleton: API is ready; polling and channel publication are intentionally not yet enabled. */
public class PseCompassHandler extends BaseThingHandler {
    protected final PseCompassClient client;

    public PseCompassHandler(Thing thing, PseCompassClient client) {
        super(thing);
        this.client = client;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                "API client available; polling and channel publication are not implemented yet");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Read-only skeleton; there are no command channels.
    }
}
