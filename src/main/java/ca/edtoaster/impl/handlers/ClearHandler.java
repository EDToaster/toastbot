package ca.edtoaster.impl.handlers;

import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.annotations.Command;
import ca.edtoaster.commands.InteractionHandler;
import ca.edtoaster.annotations.Option;
import ca.edtoaster.commands.data.SlashInteractionData;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.object.entity.Message;
import discord4j.discordjson.json.ImmutableBulkDeleteRequest;
import discord4j.rest.service.ChannelService;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


@Log4j2
public class ClearHandler extends InteractionHandler {
    private final Snowflake namespace;
    private final DiscordClient discordClient;

    public ClearHandler(Snowflake namespace, DiscordClient discordClient) {
        this.namespace = namespace;
        this.discordClient = discordClient;
    }

    public Mono<Integer> deleteMessages(List<Message> messages) {
        ChannelService service = discordClient.getChannelService();
        int numMessages = messages.size();
        log.info(String.format("Deleting %d messages", numMessages));

        List<String> messageIDs = messages
                .stream()
                .map(Message::getId)
                .map(Snowflake::asString)
                .collect(Collectors.toList());
        log.info(String.format("IDs: %d", messageIDs.size()));

        if (numMessages == 1) {
            return messages.get(0).getChannel()
                    .flatMap(channel -> service.deleteMessage(
                            channel.getId().asLong(),
                            messages.get(0).getId().asLong(), null))
                    .thenReturn(numMessages);
        } else if (numMessages > 1) {
            return messages.get(0).getChannel()
                    .flatMap(channel -> service
                            .bulkDeleteMessages(
                                    channel.getId().asLong(),
                                    ImmutableBulkDeleteRequest.of(messageIDs)))
                    .thenReturn(numMessages);
        } else {
            return Mono.just(0);
        }
    }

    private String getDeletedString(int num) {
        if (num == 0) {
            return "No messages deleted";
        } else if (num == 1) {
            return "1 message deleted";
        } else {
            return String.format("%d messages deleted", num);
        }
    }

    @Command(description = "Clear all messages from the bot")
    public Mono<Void> clear(
            SlashInteractionData data,
            @Option(name = "n", description = "Num messages", required = false)
                    Long n) {
        SlashCommandEvent event = data.getEvent();
        // check the last 100 messages in the channel
        if (Objects.isNull(n)) n = 100L;
        log.info("Clear! " + n);

        return data
                .getEvent()
                .getInteraction()
                .getChannel()
                .flatMapMany((channel) -> channel.getMessagesBefore(Snowflake.of(Instant.now())))
                .take(n)
                .doOnNext(m -> log.info(m.getId().asString()))
                .filter(message -> message.getAuthor().map(data.getBotUser()::equals).orElse(false))
                .take(100)
                .doOnNext(m -> log.info("authored -- " + m.getId().asString()))
                .collect(Collectors.toList())
                .flatMap(this::deleteMessages)
                .flatMap(numDeleted -> event.replyEphemeral(getDeletedString(numDeleted)));
    }

    public static InteractionHandlerSpec getInteractionHandlerSpec() {
        return new InteractionHandlerSpec(ClearHandler.class, ClearHandler::new);
    }
}
