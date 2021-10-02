package ca.edtoaster.impl.handlers;

import ca.edtoaster.commands.InteractionHandler;
import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.annotations.Command;
import ca.edtoaster.commands.data.SlashInteractionData;
import ca.edtoaster.util.Utils;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class StatusHandler extends InteractionHandler {
    private final Snowflake namespace;
    private final DiscordClient discordClient;

    @Command(description = "Configuration")
    public Mono<Void> config(SlashInteractionData data) {
        SlashCommandEvent event = data.getEvent();
        return event.replyEphemeral(String.format("Namespace: %s\nServer Invite Link: %s", namespace.asString(), Utils.getServerInviteLink(data.getBotUser())));
    }

    public static InteractionHandlerSpec getInteractionHandlerSpec() {
        return new InteractionHandlerSpec(StatusHandler.class, StatusHandler::new);
    }
}
