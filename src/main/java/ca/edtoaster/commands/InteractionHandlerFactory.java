package ca.edtoaster.commands;


import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;

@FunctionalInterface
public interface InteractionHandlerFactory {
    Object create(Snowflake namespace, DiscordClient discordClient);
}
