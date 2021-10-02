package ca.edtoaster.audio;

import com.codepoetics.protonpack.StreamUtils;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import discord4j.core.object.entity.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Log4j2
public class TrackScheduler extends AudioEventAdapter {

    private static final String SEARCH_PREFIX = "ytsearch:";

    @RequiredArgsConstructor
    @Getter
    private class AudioTrackContainer {
        private final User requester;
        private final AudioTrack track;
    }

    private final Queue<AudioTrackContainer> upNext;
    private final AudioPlayerManager manager;
    private final AudioPlayer player;

    @Getter
    private final LavaPlayerAudioProvider provider;

    public TrackScheduler(AudioPlayerManager manager) {
        this.upNext = new ArrayDeque<>();
        this.manager = manager;
        this.player = manager.createPlayer();
        player.setVolume(40);
        this.player.addListener(this);
        this.provider = new LavaPlayerAudioProvider(player);
    }

    // public methods

    public int setVolume(int vol) {
        player.setVolume(vol);
        return getVolume();
    }

    public int getVolume() {
        return player.getVolume();
    }

    public boolean queueIsEmpty() {
        return upNext.isEmpty();
    }

    public boolean hasTrackPlaying() {
        return Objects.nonNull(player.getPlayingTrack());
    }

    public boolean isPaused() {
        return player.isPaused();
    }

    public void togglePause() {
        player.setPaused(!player.isPaused());
    }

    public void stop() {
        player.stopTrack();
    }

    public void clearQueue() {
        this.upNext.clear();
    }

    public Flux<AudioTrackInfo> tryQueue(String id, User requester) {
        // check if ID is an url
        final String searchTerm;
        if (isURL(id)) {
            searchTerm = id;
        } else {
            searchTerm = SEARCH_PREFIX + id;
        }

        log.info("Try queue");
        return Flux.create(sink -> {
            this.manager.loadItem(searchTerm, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    log.info("Track loaded " + track.getIdentifier());
                    if (!player.startTrack(track, true)) {
                        upNext.offer(new AudioTrackContainer(requester, track));
                    }
                    sink.next(track.getInfo());
                    sink.complete();
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    log.info("Loaded playlist " + playlist.getName());

                    if (playlist.isSearchResult()) {
                        log.info("Search result");
                        List<AudioTrack> tracks = playlist.getTracks();
                        if (tracks.size() > 0) {
                            trackLoaded(tracks.get(0));
                        }
                    } else {
                        // add all tracks to queue
                        for (AudioTrack track : playlist.getTracks()) {
                            // todo: fix hackiness
                            if (!player.startTrack(track, true)) {
                                upNext.offer(new AudioTrackContainer(requester, track));
                            }
                            sink.next(track.getInfo());
                        }
                        sink.complete();
                    }
                }

                @Override
                public void noMatches() {
                    sink.error(new IllegalAccessError(String.format("No matches for song %s", id)));
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    sink.error(new IllegalAccessError(String.format("Could not load song %s", id)));
                }
            });
        });
    }

    public Mono<AudioTrack> skip() {
        log.info("Track skipping");
        AudioTrack currentTrack = player.getPlayingTrack();
        AudioTrackContainer nextTrackContainer = this.upNext.poll();
        player.startTrack(nextTrackContainer == null ? null : nextTrackContainer.track, false);
        return Mono.justOrEmpty(currentTrack);
    }

    private String format(long millis) {
        final long hr = TimeUnit.MILLISECONDS.toHours(millis);
        final long min = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        final long sec = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        return String.format("%02d:%02d:%02d", hr, min, sec);
    }

    public String getCurrentlyPlaying() {
        AudioTrack currentTrack = player.getPlayingTrack();

        StringBuilder builder = new StringBuilder();

        if (Objects.nonNull(currentTrack)) {
            builder.append(
                    String.format("`%s [%s]/[%s]`",
                            currentTrack.getInfo().title,
                            format(currentTrack.getPosition()),
                            format(currentTrack.getDuration())));
        }

        return builder.toString();
    }

    public String getUpNext() {
        if (queueIsEmpty()) {
            return "There are no songs left in the queue";
        } else {
            return StreamUtils.zipWithIndex(upNext.stream())
                        .limit(20)
                        .map(i -> String.format("%d -- %s",
                                i.getIndex() + 1,
                                i.getValue().getTrack().getInfo().title))
                        .collect(Collectors.joining("\n"));
        }
    }


    // callbacks and stuff

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext) {
            log.info("OnTrackEnd called");
            skip();
        }

        // endReason == FINISHED: A track finished or died by an exception (mayStartNext = true).
        // endReason == LOAD_FAILED: Loading of a track failed (mayStartNext = true).
        // endReason == STOPPED: The player was stopped.
        // endReason == REPLACED: Another track started playing while this had not finished
        // endReason == CLEANUP: Player hasn't been queried for a while, if you want you can put a
        //                       clone of this back to your queue
    }

    private static boolean isURL(String url) {
        try {
            new URL(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}