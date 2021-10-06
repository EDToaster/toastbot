package ca.edtoaster.commands.data;

import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.interaction.ButtonInteractEvent;
import discord4j.core.object.entity.User;
import lombok.Data;

import java.util.function.Consumer;

@Data
public class ButtonInteractionData {
    private final Snowflake namespace;
    private final User who;
    private final User botUser;
    private final ButtonInteractEvent event;

    public void log(Consumer<String> logger) {
        logger.accept(String.format("Button interaction event received from %s (%s)", who.getUsername(), who.getId().asString()));
        logger.accept(String.format("In Guild %s", namespace.asString()));
    }
}
