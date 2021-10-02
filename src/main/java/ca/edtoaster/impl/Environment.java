package ca.edtoaster.impl;

import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
public class Environment {
    private final Map<String, String> env;

    public static final String DISCORD_TOKEN = "DISCORD_TOKEN";
    public static final String USER_DIR = "user.dir";

    private Optional<String> fetch(String key) {
        return Optional.ofNullable(env.getOrDefault(key, null));
    }

    public Optional<String> getDiscordToken() {
        return fetch(DISCORD_TOKEN);
    }

    public Optional<String> getUserDir() {
        return fetch(USER_DIR);
    }
}
