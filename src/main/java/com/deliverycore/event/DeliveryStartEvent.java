package com.deliverycore.event;

import com.deliverycore.service.ActiveEvent;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Published after a new delivery becomes active. */
public final class DeliveryStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ActiveEvent delivery;

    public DeliveryStartEvent(ActiveEvent delivery) {
        this.delivery = delivery;
    }

    public ActiveEvent getActiveEvent() {
        return delivery;
    }

    public String getDeliveryName() {
        return delivery.getDeliveryName();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
