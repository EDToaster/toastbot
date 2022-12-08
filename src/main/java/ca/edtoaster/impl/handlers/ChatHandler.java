package ca.edtoaster.impl.handlers;

import ca.edtoaster.annotations.Command;
import ca.edtoaster.annotations.CommandNamespace;
import ca.edtoaster.annotations.Option;
import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.commands.MessageHandler;
import ca.edtoaster.commands.data.ApplicationCommandInteractionData;
import ca.edtoaster.commands.data.MessageCreateData;
import ca.edtoaster.util.ChatGPT;
import ca.edtoaster.util.ChatRequest;
import ca.edtoaster.util.ChatResponse;
import ca.edtoaster.util.ResponseId;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.interaction.ApplicationCommandInteractionEvent;
import discord4j.core.object.command.Interaction;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.ThreadChannel;
import discord4j.core.spec.MessageCreateSpec;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@CommandNamespace(name="chat", description = "ChatGPT configuration commands")
public class ChatHandler implements MessageHandler {

    private final Snowflake namespace;
    private final DiscordClient discordClient;
    @Nullable
    private MessageChannel monitoredChannel;
    private final ChatGPT chat;

    private String chatGPTToken;

    public ChatHandler(Snowflake namespace, DiscordClient discordClient) {
        this.namespace = namespace;
        this.discordClient = discordClient;
        this.chat = new ChatGPT();
    }

    @Command(description = "Setup bot to use this channel")
    public Mono<Void> summon(ApplicationCommandInteractionData data,
                             @Option(name="token", description="ChatGPT auth bearer token")
                             String token) {
        // ack and send new message
        ApplicationCommandInteractionEvent event = data.getEvent();
        Interaction interaction = event.getInteraction();
        return event.reply("Showing queue").withEphemeral(true)
                .then(interaction.getChannel())
                .doOnNext(c -> monitorChannel(c, token))
//                .flatMap(c -> chat.query(new ChatRequest("f3c5cc77-272a-4572-b994-b7e71d0a939a", "hello")))
//                .flatMap(s -> this.channel.get().createMessage(s))
                .then();
    }

    private void monitorChannel(MessageChannel channel, String token) {
        this.monitoredChannel = channel;
        this.chatGPTToken = token;
    }

    public static InteractionHandlerSpec getInteractionHandlerSpec() {
        return new InteractionHandlerSpec(ChatHandler.class, ChatHandler::new);
    }

    @Override
    public Mono<Void> handleMessageCreate(MessageCreateData data) {
        var event = data.getEvent();
        var message = event.getMessage();

        if (data.getBotUser().equals(data.getWho())) {
            return Mono.empty();
        }

        // check if channel is a threadchannel within monitored channel

        return message.getChannel()
                .cast(ThreadChannel.class)
                .filterWhen(c -> c.getParent().map(parent -> parent.equals(monitoredChannel)))
                // get parent id
                .flatMap(c -> getLastIdsInThread(c, message.getTimestamp(), data.getBotUser()).zipWith(Mono.just(c)))
                .flatMap(c -> chat
                                .query(new ChatRequest(c.getT1(), message.getContent()), chatGPTToken)
                                .map(this::createFormattedResponse)
                                .flatMap(c.getT2()::createMessage))
                .then();
    }

    private static final Pattern ID_PATTERN = Pattern.compile("^\\|\\|\\(([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}),([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\)\\|\\|.*");

    /**
     * Returns mono of Tuple(conversation, parent)
     */
    public Mono<ResponseId> getLastIdsInThread(ThreadChannel channel, Instant time, User user) {
        return channel.getMessagesBefore(Snowflake.of(time))
                .take(10)
                // filter out exclude user
                .filter(m -> m.getAuthor().map(user::equals).orElse(false))
                .mapNotNull(m -> {
                    Matcher matcher = ID_PATTERN.matcher(m.getContent());
                    if (matcher.find()) {
                        log.info("Found matching (conversation,parent) = (" + matcher.group(1) + ", " + matcher.group(2) + ")");
                        return new ResponseId(matcher.group(1), matcher.group(2));
                    }
                    return null;
                })
                .next()
                .switchIfEmpty(Mono.just(new ResponseId(null, UUID.randomUUID().toString())));
    }

    public MessageCreateSpec createFormattedResponse(String data) {
        ChatResponse response = ChatResponse.parse(data);

        return MessageCreateSpec.create()
                .withContent(createContent(response));
    }

    public String createContent(ChatResponse response) {
        if (response == null) return "Something went wrong...";

        return String.format("||(%s,%s)||%n%s", response.getId().getConversationId(), response.getId().getParentId(), response.getMessage());
    }
}
