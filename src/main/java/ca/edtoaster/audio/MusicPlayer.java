package ca.edtoaster.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MusicPlayer {

    /*
    Volume Controls
     */

    void setVolume(int vol);
    int getVolume();

    /*
    Queue Controls
     */

    boolean isQueueEmpty();
    void clearQueue();

    /**
     * Queue up a song, then return all songs that were added
     */
    Flux<AudioTrackInfo> queueTracks(String queryString);
    Mono<AudioTrack> restartTrack();

    /**
     * Skip the current track, and return the skipped track
     */
    Mono<AudioTrack> skipTrack();

    // Is there any track currently queued up, even if the player is paused?
    boolean isTrackPlaying();

    /*
    Pause Controls
     */

    boolean isPaused();
    void togglePause();
    void setPaused(boolean paused);


    void resetPlayer();
}