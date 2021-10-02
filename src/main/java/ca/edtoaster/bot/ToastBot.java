package ca.edtoaster.bot;

import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.partition.Partition;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.event.domain.interaction.ButtonInteractEvent;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.object.entity.Role;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.rest.util.ApplicationCommandOptionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.*;

@Log4j2
@RequiredArgsConstructor
public class ToastBot implements Runnable {
    public static final Map<Class<?>, ApplicationCommandOptionType> typesMap;
    static {
        typesMap = new HashMap<>();
        typesMap.put(String.class, ApplicationCommandOptionType.STRING);
        typesMap.put(Long.class, ApplicationCommandOptionType.INTEGER);
        typesMap.put(Boolean.class, ApplicationCommandOptionType.BOOLEAN);
        typesMap.put(User.class, ApplicationCommandOptionType.USER);
        typesMap.put(Channel.class, ApplicationCommandOptionType.CHANNEL);
        typesMap.put(Role.class, ApplicationCommandOptionType.ROLE);

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
        log.info("Running bot");
        this.discordClient = DiscordClient.create(token);
        this.gatewayDiscordClient = discordClient.login().blockOptional().orElseThrow();
        this.botUser = gatewayDiscordClient.getSelf().blockOptional().orElseThrow();
        this.partitionMap = new HashMap<>();

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
            public Publisher<?> onButtonInteract(ButtonInteractEvent event) {
                log.info("Got button interaction event!");

                // check guild id
                Snowflake guildID = event.getInteraction().getGuildId().orElse(null);

                if (Objects.isNull(guildID)) {
                    log.info("Event was not sent from a guild");
                    return event.replyEphemeral("Commands to this bot must be sent from inside a server");
                }

                Partition partition = partitionMap.getOrDefault(guildID, null);
                if (Objects.isNull(partition)) {
                    log.info("Partition " + guildID.asString() + " not found");
                    return event.replyEphemeral("Something went wrong!");
                }

                return partition.handleButton(event);
            }

            @Override
            public Publisher<?> onSlashCommand(SlashCommandEvent event) {
                log.info("Got event");

                // check guild id
                Snowflake guildID = event.getInteraction().getGuildId().orElse(null);
                if (Objects.isNull(guildID)) {
                    log.info("Event was not sent from a guild");
                    return event.replyEphemeral("Commands to this bot must be sent from inside a server");
                }

                Partition partition = partitionMap.getOrDefault(guildID, null);
                if (Objects.isNull(partition)) {
                    log.info("Partition " + guildID.asString() + " not found");
                    return event.replyEphemeral("Something went wrong!");
                }

                return partition.handleSlash(event);
            }
        }).blockLast();
    }
}
