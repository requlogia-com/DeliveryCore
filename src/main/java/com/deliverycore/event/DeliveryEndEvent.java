package com.deliverycore.event;

import com.deliverycore.model.Winner;
import com.deliverycore.service.ActiveEvent;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/** Published once a delivery closes and its winners have been calculated. */
public final class DeliveryEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ActiveEvent delivery;
    private final List<Winner> winners;

    public DeliveryEndEvent(ActiveEvent delivery, List<Winner> winners) {
        this.delivery = delivery;
        this.winners = List.copyOf(winners);
    }

    public ActiveEvent getActiveEvent() {
        return delivery;
    }

    public String getDeliveryName() {
        return delivery.getDeliveryName();
    }

    public List<Winner> getWinners() {
        return winners;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
