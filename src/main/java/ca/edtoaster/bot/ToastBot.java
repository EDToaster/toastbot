package ca.edtoaster.bot;

import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.partition.Partition;
import ca.edtoaster.util.Utils;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.event.domain.interaction.ApplicationCommandInteractionEvent;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.entity.Role;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Log4j2
@RequiredArgsConstructor
public class ToastBot implements Runnable {
    public static final Map<Class<?>, ApplicationCommandOption.Type> typesMap;
    static {
        typesMap = new HashMap<>();
        typesMap.put(String.class, ApplicationCommandOption.Type.STRING);
        typesMap.put(Long.class, ApplicationCommandOption.Type.INTEGER);
        typesMap.put(Boolean.class, ApplicationCommandOption.Type.BOOLEAN);
        typesMap.put(User.class, ApplicationCommandOption.Type.USER);
        typesMap.put(Channel.class, ApplicationCommandOption.Type.CHANNEL);
        typesMap.put(Role.class, ApplicationCommandOption.Type.ROLE);

        /*
        SUB_COMMAND(1),
        SUB_COMMAND_GROUP(2),
        STRING(3),
        INTEGER(4),
        BOOLEAN(5),
        USER(6),
        CHANNEL(7),
        ROLE(8),
        MENTIONABLE(9);
        */
    }

    private final String token;
    private final List<InteractionHandlerSpec> interactionHandlerSpecs;

    private DiscordClient discordClient;
    private GatewayDiscordClient gatewayDiscordClient;
    private User botUser;

    private Map<Snowflake, Partition> partitionMap;

    @Override
    public void run() {
        log.info("Running bot with token \"" + token + "\"");
        this.discordClient = DiscordClient.create(token);
        this.gatewayDiscordClient = discordClient.login().blockOptional().orElseThrow();
        this.botUser = gatewayDiscordClient.getSelf().blockOptional().orElseThrow();
        this.partitionMap = new HashMap<>();

        log.info("Server Invite link: " + Utils.getServerInviteLink(botUser));

        // refresh commands and
        gatewayDiscordClient.getGuilds()
                .doOnNext(g -> log.info(String.format("Refreshing guild %s", g.toString())))
                .map(g -> new Partition(g.getId(), botUser, discordClient, interactionHandlerSpecs))
                .doOnNext(p -> this.partitionMap.put(p.getNamespace(), p))
                .flatMap(Partition::refreshGuild)
                .blockLast();

        gatewayDiscordClient.on(new ReactiveEventAdapter() {
            @Override
            public Publisher<?> onReady(ReadyEvent event) {
                log.info("Client ready!");
                return Mono.empty();
            }

            @Override
            public Publisher<?> onButtonInteraction(ButtonInteractionEvent event) {
                log.info("Got button interaction event!");

                // check guild id
                Snowflake guildID = event.getInteraction().getGuildId().orElse(null);

                if (Objects.isNull(guildID)) {
                    log.info("Event was not sent from a guild");
                    return event.reply("Commands to this bot must be sent from inside a server").withEphemeral(true);
                }

                Partition partition = partitionMap.getOrDefault(guildID, null);
                if (Objects.isNull(partition)) {
                    log.info("Partition " + guildID.asString() + " not found");
                    return event.reply("Something went wrong!").withEphemeral(true);
                }

                return partition.handleButton(event);
            }

            @Override
            public Publisher<?> onApplicationCommandInteraction(ApplicationCommandInteractionEvent event) {
                log.info("Got event");

                // check guild id
                Snowflake guildID = event.getInteraction().getGuildId().orElse(null);
                if (Objects.isNull(guildID)) {
                    log.info("Event was not sent from a guild");
                    return event.reply("Commands to this bot must be sent from inside a server").withEphemeral(true);
                }

                Partition partition = partitionMap.getOrDefault(guildID, null);
                if (Objects.isNull(partition)) {
                    log.info("Partition " + guildID.asString() + " not found");
                    return event.reply("Something went wrong!").withEphemeral(true);
                }

                return partition.handleSlash(event);
            }

            @Override
            public Publisher<?> onMessageCreate(MessageCreateEvent event) {
                log.info("Got message");

                // check guild id
                Snowflake guildID = event.getGuildId().orElse(null);
                if (Objects.isNull(guildID)) {
                    log.info("Event was not sent from a guild, or was a private message (ephemeral)");
                    return Mono.empty();
                }

                Partition partition = partitionMap.getOrDefault(guildID, null);
                if (Objects.isNull(partition)) {
                    log.info("Partition " + guildID.asString() + " not found");
                    return event.getMessage().getChannel().flatMap(c ->
                            c.createMessage("Whoa! Something went truly wrong. Reach out to my author to fix me!"));
                }

                return partition.handleMessageCreate(event);
            }
        }).blockLast();
    }
}
