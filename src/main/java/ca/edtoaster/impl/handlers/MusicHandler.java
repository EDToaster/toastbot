package ca.edtoaster.impl.handlers;

import ca.edtoaster.audio.TrackScheduler;
import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.annotations.ButtonListener;
import ca.edtoaster.annotations.Command;
import ca.edtoaster.commands.InteractionHandler;
import ca.edtoaster.annotations.Option;
import ca.edtoaster.commands.data.ButtonInteractionData;
import ca.edtoaster.commands.data.SlashInteractionData;
import ca.edtoaster.commands.data.Whatever;
import ca.edtoaster.impl.ExposingAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
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
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import discord4j.rest.interaction.InteractionResponse;
import discord4j.rest.util.Color;
import discord4j.voice.VoiceConnection;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


@Log4j2
public class MusicHandler extends InteractionHandler {
    private final Snowflake namespace;
    private final DiscordClient discordClient;

    private final TrackScheduler trackScheduler;
    private final ExposingAudioPlayerManager playerManager;

    // Keeps the previous queue type interactions here, to delete later.
    private final List<InteractionResponse> previousQueueInteractions;

    // stateful stuff, like audio connections
    VoiceConnection currentVoiceConnection;

    private enum PlayPauseControl {
        PLAY_PAUSE("PLAY_PAUSE"),
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
        this.previousQueueInteractions = new ArrayList<>();

        this.playerManager = new ExposingAudioPlayerManager();
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerRemoteSources(playerManager);

        this.trackScheduler = new TrackScheduler(playerManager);
    }

    private Mono<Void> deletePreviousQueueMessages() {
        List<InteractionResponse> prev = List.copyOf(this.previousQueueInteractions);
        this.previousQueueInteractions.clear();

        return Flux.fromIterable(prev)
                .flatMap(InteractionResponse::deleteInitialResponse)
                .onErrorContinue((t, o) -> {}) // no op on error because it's probably because prev queue is deleted already
                .then(Mono.empty());
    }

    private InteractionApplicationCommandCallbackSpec constructQueueMessage(InteractionApplicationCommandCallbackSpec m) {
        String currentlyPlaying = this.trackScheduler.getCurrentlyPlaying();
        String queue = this.trackScheduler.getUpNext();

        List<Button> buttons = List.of(
                Button.primary(PlayPauseControl.PLAY_PAUSE.getButtonID(),
                        trackScheduler.isPaused() ? ReactionEmoji.unicode("\u25B6") : ReactionEmoji.unicode("\u23F8"),
                        trackScheduler.isPaused() ? "Resume" : "Pause").disabled(!trackScheduler.hasTrackPlaying()),
                Button.primary(PlayPauseControl.SKIP.getButtonID(), ReactionEmoji.unicode("\u23ED"), "Skip").disabled(!trackScheduler.hasTrackPlaying()),
                Button.danger(PlayPauseControl.CLEAR.getButtonID(), ReactionEmoji.unicode("\u2755"), "Clear Queue").disabled(trackScheduler.queueIsEmpty()),
                Button.danger(PlayPauseControl.KILL.getButtonID(), ReactionEmoji.unicode("\u2620"), "Disconnect")
        );

        return m.addEmbed(embed -> {
            embed.setTitle("Current Queue")
                    .setDescription(currentlyPlaying + queue)
                    .setColor(Color.MOON_YELLOW);
            }).setComponents(ActionRow.of(buttons));
    }

    private InteractionApplicationCommandCallbackSpec constructDisconnectMessage(InteractionApplicationCommandCallbackSpec m) {
        return m.setContent("Bye!");
    }

    @Command(description = "Show the queue")
    public Mono<Void> q(SlashInteractionData data) {
        return deletePreviousQueueMessages()
                .doOnSuccess((v) -> this.previousQueueInteractions.add(data.getEvent().getInteractionResponse()))
                .then(data.getEvent().reply(this::constructQueueMessage));
    }

    public Mono<AudioTrack> skip() {
        return Mono.justOrEmpty(this.currentVoiceConnection)
                .flatMap(v -> trackScheduler.skip());
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
            case SKIP:
                return skip().then(event.edit(this::constructQueueMessage));
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

    @Command(description = "Play a song")
    public Mono<Void> play(SlashInteractionData data,
                           @Option(name="video", description="Youtube video link or playlist")
                           String url) {
        SlashCommandEvent event = data.getEvent();
        return Mono.justOrEmpty(this.currentVoiceConnection)
                .flatMap(v -> trackScheduler.tryQueue(url, data.getWho())
                        .take(20)
                        .map(a -> "- " + a.title)
                        .collect(Collectors.toList())
                        .map(l -> l.isEmpty() ? "No song found" : "Songs put in queue:\n" + String.join("\n", l))
                        .onErrorReturn("No song found")
                        .doOnNext(log::info))
                .flatMap(titles -> event.reply(titles).then(emit()))
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

        return data.getEvent().replyEphemeral(String.format("Volume set to %d", trackScheduler.setVolume(vol.intValue())));
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
        trackScheduler.clearQueue();
        trackScheduler.stop();

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
