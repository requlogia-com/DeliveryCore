package com.deliverycore.service;

import com.deliverycore.config.DeliveryConfig;
import com.deliverycore.event.DeliveryEndEvent;
import com.deliverycore.model.PlaceholderContext;
import com.deliverycore.model.RewardConfig;
import com.deliverycore.reward.RewardService;
import org.bukkit.Bukkit;

import java.util.List;

/** Runs the console commands configured for each imported delivery rank. */
public final class ImportedDeliveryRewards {
    private ImportedDeliveryRewards() {}

    public static void distribute(DeliveryConfig config, DeliveryEndEvent event, RewardService rewardService) {
        var delivery = config.getImportedDeliveries().get(event.getDeliveryName());
        if (delivery == null || delivery.rankingRewards().isEmpty()) return;

        var active = event.getActiveEvent();
        var context = PlaceholderContext.empty()
                .withEvent(active.getResolvedCategory(), active.getResolvedItem(), event.getDeliveryName())
                .withTiming(active.getStartTime(), active.getEndTime(), active.getTimezone())
                .withWinnerDetails(event.getWinners());
        for (var winner : event.getWinners()) {
            List<String> commands = delivery.rankingRewards().get(winner.rank());
            if (commands == null || commands.isEmpty()) continue;

            var player = Bukkit.getPlayer(winner.playerUuid());
            if (player == null) {
                rewardService.storePendingReward(winner.playerUuid(), event.getDeliveryName(), RewardConfig.command(commands));
            } else {
                rewardService.executeRewardCommands(winner.playerUuid(), player.getName(), commands, context,
                        command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            }
        }
    }
}
