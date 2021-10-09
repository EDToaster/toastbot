package ca.edtoaster.audio;

import ca.edtoaster.impl.handlers.MusicHandler;
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
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Log4j2
public class TrackScheduler extends AudioEventAdapter implements MusicPlayer {

    private static final String SEARCH_PREFIX = "ytsearch:";

    private final Deque<AudioTrack> upNext;
    private final AudioPlayerManager manager;
    private final AudioPlayer player;

    @Getter
    private final LavaPlayerAudioProvider provider;

    private final MusicHandler handler;

    public TrackScheduler(AudioPlayerManager manager, MusicHandler handler) {
        this.upNext = new ArrayDeque<>();
        this.manager = manager;
        this.player = manager.createPlayer();
        player.setVolume(40);
        this.player.addListener(this);
        this.provider = new LavaPlayerAudioProvider(player);
        this.handler = handler;
    }

    // public methods

    public void setVolume(int vol) {
        player.setVolume(vol);
    }

    public int getVolume() {
        return player.getVolume();
    }

    public boolean isQueueEmpty() {
        return upNext.isEmpty();
    }

    public boolean isTrackPlaying() {
        return Objects.nonNull(player.getPlayingTrack());
    }


    public void togglePause() {
        setPaused(!isPaused());
    }

    public boolean isPaused() {
        return player.isPaused();
    }
    public void setPaused(boolean paused) {
        player.setPaused(paused);
    }

    public void resetPlayer() {
        this.clearQueue();
        player.stopTrack();
    }

    public void clearQueue() {
        this.upNext.clear();
    }

    public Flux<AudioTrackInfo> queueTracks(String queryString) {
        // check if ID is an url
        final String searchTerm;
        if (isURL(queryString)) {
            searchTerm = queryString;
        } else {
            searchTerm = SEARCH_PREFIX + queryString;
        }

        log.info("Try queue");
        return Flux.create(sink -> {
            this.manager.loadItem(searchTerm, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    log.info("Track loaded " + track.getIdentifier());
                    if (!player.startTrack(track.makeClone(), true)) {
                        upNext.offer(track);
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
                            if (!player.startTrack(track.makeClone(), true)) {
                                upNext.offer(track);
                            }
                            sink.next(track.getInfo());
                        }
                        sink.complete();
                    }
                }

                @Override
                public void noMatches() {
                    sink.error(new IllegalAccessError(String.format("No matches for song %s", queryString)));
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    sink.error(new IllegalAccessError(String.format("Could not load song %s", queryString)));
                }
            });
        });
    }

    public Mono<AudioTrack> restartTrack() {
        log.info("Refreshing current track");
        AudioTrack currentTrack = player.getPlayingTrack();
        currentTrack = currentTrack == null ? null : currentTrack.makeClone();
//        if (Objects.nonNull(currentTrack)) {
//            this.upNext.offerFirst(currentTrack);
//        }

        player.startTrack(currentTrack, false);
        return Mono.justOrEmpty(currentTrack);
    }

    public Mono<AudioTrack> skipTrack() {
        log.info("Track skipping");
        AudioTrack currentTrack = player.getPlayingTrack();
        AudioTrack track = this.upNext.poll();
        player.startTrack(track == null ? null : track.makeClone(), false);
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
        if (isQueueEmpty()) {
            return "There are no songs left in the queue";
        } else {
            int numUpNext = upNext.size();
            int andMore = numUpNext - 20;
            String content = StreamUtils.zipWithIndex(upNext.stream())
                        .limit(20)
                        .map(i -> String.format(":small_blue_diamond: %s",
                                i.getValue().getInfo().title))
                        .collect(Collectors.joining("\n"));
            if (andMore > 0) {
                content = content + "\nand " + andMore + " more...";
            }
            return content;
        }
    }


    // callbacks and stuff

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        log.info("OnTrackEnd called");
        if (endReason.mayStartNext) {
            log.info("may start next called");
            skipTrack().then(this.handler.refreshQueueMessages()).subscribe();
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