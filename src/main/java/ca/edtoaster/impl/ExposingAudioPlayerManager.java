package ca.edtoaster.impl;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ExposingAudioPlayerManager extends DefaultAudioPlayerManager {
    private List<AudioSourceManager> managersCopy;

    public ExposingAudioPlayerManager() {
        super();
        managersCopy = new ArrayList<>();
    }

    public List<String> getManagers() {
        return managersCopy.stream().map(AudioSourceManager::getSourceName).collect(Collectors.toList());
    }

    @Override
    public void registerSourceManager(AudioSourceManager sourceManager) {
        // make a copy
        managersCopy.add(sourceManager);
        super.registerSourceManager(sourceManager);
    }
}
