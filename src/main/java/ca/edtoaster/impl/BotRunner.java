package ca.edtoaster.impl;

import ca.edtoaster.bot.ToastBot;
import ca.edtoaster.impl.handlers.ClearHandler;
import ca.edtoaster.impl.handlers.MusicHandler;
import ca.edtoaster.impl.handlers.StatusHandler;
import lombok.RequiredArgsConstructor;

import java.util.List;

import static ca.edtoaster.impl.Environment.DISCORD_TOKEN;

@RequiredArgsConstructor
public class BotRunner implements Runnable {
    private final Environment env;

    @Override
    public void run() {
        String token = env.getDiscordToken().orElseThrow(
                () -> new IllegalArgumentException(String.format("%s was not provided in environment variables", DISCORD_TOKEN)));

        new ToastBot(token, List.of(
                ClearHandler.getInteractionHandlerSpec(),
                MusicHandler.getInteractionHandlerSpec(),
                StatusHandler.getInteractionHandlerSpec())).run();
    }
}
