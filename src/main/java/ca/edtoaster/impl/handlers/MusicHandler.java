package ca.edtoaster.impl.handlers;

import ca.edtoaster.annotations.CommandNamespace;
import ca.edtoaster.audio.TrackScheduler;
import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.annotations.ButtonListener;
import ca.edtoaster.annotations.Command;
import ca.edtoaster.annotations.Option;
import ca.edtoaster.commands.data.ButtonInteractionData;
import ca.edtoaster.commands.data.SlashInteractionData;
import ca.edtoaster.commands.data.Whatever;
import ca.edtoaster.impl.ExposingAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractEvent;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.command.Interaction;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import discord4j.rest.service.ChannelService;
import discord4j.rest.util.Color;
import discord4j.voice.VoiceConnection;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;


@Log4j2
@CommandNamespace(name="m", description = "Music commands")
public class MusicHandler {
    private final Snowflake namespace;
    private final DiscordClient discordClient;
    private final ChannelService channelService;

    private final TrackScheduler trackScheduler;
    private final ExposingAudioPlayerManager playerManager;

    // Keeps the previous queue type interactions here, to delete later.
    private final AtomicReference<Message> previousQueueMessage;


    // stateful stuff, like audio connections
    VoiceConnection currentVoiceConnection;

    private enum PlayPauseControl {
        PLAY_PAUSE("PLAY_PAUSE"),
        RESTART("RESTART"),
        SKIP("SKIP"),
        CLEAR("CLEAR"),
        KILL("KILL");

        private static final String PLAY_PAUSE_PREFIX = "PLAYPAUSE-";

        @Getter
        private final String buttonID;

        PlayPauseControl(String id) {
            this.buttonID = PLAY_PAUSE_PREFIX + id;
        }

        static PlayPauseControl of(String buttonID) {
            return Arrays.stream(values()).filter(v -> v.buttonID.equals(buttonID)).findFirst().orElse(null);
        }
    }

    public MusicHandler(Snowflake namespace, DiscordClient discordClient) {
        this.namespace = namespace;
        this.discordClient = discordClient;
        this.currentVoiceConnection = null;
        this.previousQueueMessage = new AtomicReference<>();
        this.channelService = discordClient.getChannelService();

        this.playerManager = new ExposingAudioPlayerManager();
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerRemoteSources(playerManager);

        this.trackScheduler = new TrackScheduler(playerManager, this);

        // setup recurring refreshes
        Flux.interval(Duration.ofMillis(5000))
                .flatMap(l -> this.refreshQueueMessages())
                .doOnNext(i -> log.info("Refreshed " + i + " messages"))
                .subscribe();
    }

    private InteractionApplicationCommandCallbackSpec constructQueueMessage(InteractionApplicationCommandCallbackSpec m) {
        return m.addEmbed(this::getQueueMessageEmbed).setComponents(ActionRow.of(getQueueMessageButtons()));
    }

    private EmbedCreateSpec getQueueMessageEmbed(EmbedCreateSpec embed) {
        String currentlyPlaying = this.trackScheduler.getCurrentlyPlaying();
        String queue = this.trackScheduler.getUpNext();
        return embed.setTitle(trackScheduler.isPaused() ? "Paused ..." : "Now Playing:")
                .setDescription(currentlyPlaying + "\n" + queue)
                .setColor(Color.PINK);
    }

    private List<Button> getQueueMessageButtons() {
        return List.of(
                Button.primary(PlayPauseControl.PLAY_PAUSE.getButtonID(),
                        trackScheduler.isPaused() ? ReactionEmoji.unicode("\u25B6") : ReactionEmoji.unicode("\u23F8"),
                        trackScheduler.isPaused() ? "Resume" : "Pause").disabled(!trackScheduler.isTrackPlaying()),
                Button.primary(PlayPauseControl.RESTART.getButtonID(), ReactionEmoji.unicode("\uD83D\uDD02"), "Restart").disabled(!trackScheduler.isTrackPlaying()),
                Button.primary(PlayPauseControl.SKIP.getButtonID(), ReactionEmoji.unicode("\u23ED"), "Skip").disabled(!trackScheduler.isTrackPlaying()),
                Button.danger(PlayPauseControl.CLEAR.getButtonID(), ReactionEmoji.unicode("\u2755"), "Clear Queue").disabled(trackScheduler.isQueueEmpty()),
                Button.danger(PlayPauseControl.KILL.getButtonID(), ReactionEmoji.unicode("\u2620"), "Disconnect")
        );
    }


    private InteractionApplicationCommandCallbackSpec constructDisconnectMessage(InteractionApplicationCommandCallbackSpec m) {
        return m.setContent("Bye!");
    }

    @Command(description = "Show the queue")
    public Mono<Void> q(SlashInteractionData data) {
        // ack and send new message
        SlashCommandEvent event = data.getEvent();
        Interaction interaction = event.getInteraction();
        return event.replyEphemeral("Showing queue")
                .then(this.deletePreviousQueueMessages())
                .then(interaction.getChannel())
                .flatMap(channel ->
                        channel.createMessage(spec ->
                                spec.addEmbed(this::getQueueMessageEmbed)
                                        .setComponents(ActionRow.of(getQueueMessageButtons()))))
                .doOnNext(this.previousQueueMessage::set)
                .then();
    }

    private Mono<Void> deletePreviousQueueMessages() {
        Message prev = this.previousQueueMessage.getAndSet(null);

        return Mono.justOrEmpty(prev)
                .flatMap(Message::delete)
                .onErrorResume(e -> Mono.empty()) // no op on error because it's probably because the message is deleted already
                .then(Mono.empty());
    }

    public Mono<Integer> refreshQueueMessages() {
        return Mono.justOrEmpty(this.previousQueueMessage.get())
                .flatMap(message -> message.edit(spec ->
                        spec.addEmbed(this::getQueueMessageEmbed)
                                .setComponents(ActionRow.of(getQueueMessageButtons()))))
                .onErrorResume(e -> Mono.empty())
                .doOnNext(this.previousQueueMessage::set)
                .doOnError(log::fatal)
                .map(m -> 1)
                .switchIfEmpty(Mono.just(0));
    }

    @ButtonListener(prefix = PlayPauseControl.PLAY_PAUSE_PREFIX)
    public Mono<Void> handlePlayPause(ButtonInteractionData data) {
        log.info("Play pause event gotten");
        ButtonInteractEvent event = data.getEvent();

        PlayPauseControl control = PlayPauseControl.of(event.getCustomId());

        switch(control) {
            case PLAY_PAUSE:
                trackScheduler.togglePause();
                return event.edit(this::constructQueueMessage);
            case RESTART:
                return restartTrack().then(event.edit(this::constructQueueMessage));
            case SKIP:
                return skipTrack().then(event.edit(this::constructQueueMessage));
            case CLEAR:
                trackScheduler.clearQueue();
                return event.edit(this::constructQueueMessage);
            case KILL:
                return disconnectVoiceConnection()
                        .then(event.edit(this::constructDisconnectMessage));
            default: break;
        }

        return event.acknowledge(); // will never happen unless control is null >:(
    }

    public Mono<AudioTrack> restartTrack() {
        return Mono.justOrEmpty(this.currentVoiceConnection)
                .flatMap(v -> trackScheduler.restartTrack());
    }

    public Mono<AudioTrack> skipTrack() {
        return Mono.justOrEmpty(this.currentVoiceConnection)
                .flatMap(v -> trackScheduler.skipTrack());
    }

    @Command(description = "Play a song")
    public Mono<Void> play(SlashInteractionData data,
                           @Option(name="video", description="Youtube video link or playlist")
                           String url) {
        SlashCommandEvent event = data.getEvent();
        return Mono.justOrEmpty(this.currentVoiceConnection)
                .flatMap(v -> trackScheduler.queueTracks(url)
                        .collect(Collectors.toList())
                        .map(l -> l.isEmpty() ? "No song found" : String.format("Queued %s tracks", l.size()))
                        .onErrorReturn("No song found")
                        .doOnNext(log::info))
                .flatMap(titles -> event.reply(s ->
                        s.addEmbed(e ->
                                e.setDescription(titles)
                                 .setColor(Color.MOON_YELLOW))).then(emit()))
                .switchIfEmpty(event.replyEphemeral("Bot needs to be summoned in to a voice channel").then(emit()))
                .then();
    }

    @Command(description = "Get volume of the bot. If supplied with an argument, set the volume [0-100]")
    public Mono<Void> volume(SlashInteractionData data,
                             @Option(name = "volume", description="Volume [0-100]", required = false) Long vol) {
        if (Objects.isNull(vol)) {
            return data.getEvent().replyEphemeral(String.format("Volume set to %d", trackScheduler.getVolume()));
        }

        if (vol < 0 || vol > 100) {
            return data.getEvent().replyEphemeral(String.format("Volume %d is not in range of [0-100]", vol));
        }

        trackScheduler.setVolume(vol.intValue());

        return data.getEvent().replyEphemeral(String.format("Volume set to %d", vol.intValue()));
    }

    @Command(description = "Summon the bot to join a voice channel")
    public Mono<Void> summon(SlashInteractionData data) {
        SlashCommandEvent event = data.getEvent();
        Interaction interaction = event.getInteraction();

        return Mono.justOrEmpty(interaction.getMember())
                .flatMap(Member::getVoiceState)
                .flatMap(VoiceState::getChannel)
                .doOnNext(v -> log.info("Found a voice channel!" + v.getName()))
                .flatMap(c -> c.join(spec -> spec.setProvider(trackScheduler.getProvider())))
                .doOnNext(v -> this.currentVoiceConnection = v)
                .flatMap(v -> event.replyEphemeral("Connected!").then(emit()))
                .switchIfEmpty(event.replyEphemeral("You must be in a voice channel to summon the bot!").then(emit()))
                .then();
    }

    private Mono<Whatever> disconnectVoiceConnection() {
        // clear queue
        trackScheduler.resetPlayer();

        return Mono.justOrEmpty(this.currentVoiceConnection)
                .flatMap(v -> v.disconnect().then(emit()));
    }

    @Command(description = "Disconnect the bot")
    public Mono<Void> disconnect(SlashInteractionData data) {
        SlashCommandEvent event = data.getEvent();

        log.info("Disconnecting the bot from channel");
        return disconnectVoiceConnection()
                .flatMap(_v -> event.replyEphemeral("Bye!").then(emit()))
                .switchIfEmpty(event.replyEphemeral("Cannot disconnect a disconnected bot...").then(emit()))
                .then();
    }

    @Command(description = "Get supported protocols")
    public Mono<Void> help(SlashInteractionData data) {
        SlashCommandEvent event = data.getEvent();

        log.info("Getting supported protocols");
        List<String> supportedManagers = playerManager.getManagers().stream().map(s -> "-- `" + s + "`").collect(Collectors.toList());
        return event.replyEphemeral("Supported audio sources are:\n" + String.join("\n", supportedManagers));
    }

    public static InteractionHandlerSpec getInteractionHandlerSpec() {
        return new InteractionHandlerSpec(MusicHandler.class, MusicHandler::new);
    }

    private Mono<Whatever> emit() {
        return Mono.just(new Whatever());
    }
}
