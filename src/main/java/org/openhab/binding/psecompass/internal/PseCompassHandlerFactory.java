/**
 * Copyright (c) 2026 Contributors to the openHAB project
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.psecompass.internal;

import org.openhab.binding.psecompass.internal.api.PseCompassClient;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = ThingHandlerFactory.class, configurationPid = "binding.psecompass")
public class PseCompassHandlerFactory extends BaseThingHandlerFactory {
    private final HttpClientFactory httpClientFactory;

    @Activate
    public PseCompassHandlerFactory(@Reference HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return PseCompassBindingConstants.THING_TYPE_COMPASS.equals(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        if (supportsThingType(thing.getThingTypeUID())) {
            return new PseCompassHandler(thing, new PseCompassClient(httpClientFactory.getCommonHttpClient()));
        }
        return null;
    }
}
