package ca.edtoaster.impl;

import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
public class Environment {
    private final Map<String, String> env;

    public static final String DISCORD_TOKEN = "DISCORD_TOKEN";
    public static final String USER_DIR = "user.dir";

    private Optional<String> getOptional(String key) {
        return Optional.ofNullable(env.getOrDefault(key, null));
    }

    private String getOrThrow(String key) {
        return getOptional(key).orElseThrow();
    }

    public String getDiscordToken() {
        return getOrThrow(DISCORD_TOKEN);
    }

    public String getUserDir() {
        return getOrThrow(USER_DIR);
    }
}
