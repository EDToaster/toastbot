package ca.edtoaster.impl;

import ca.edtoaster.bot.ToastBot;
import ca.edtoaster.impl.handlers.ChatHandler;
import ca.edtoaster.impl.handlers.UtilityHandler;
import ca.edtoaster.impl.handlers.MusicHandler;
import lombok.RequiredArgsConstructor;

import java.util.List;

import static ca.edtoaster.impl.Environment.DISCORD_TOKEN;

@RequiredArgsConstructor
public class BotRunner implements Runnable {
    private final Environment env;

    @Override
    public void run() {
        String token = env.getDiscordToken();

        new ToastBot(token, List.of(
                UtilityHandler.getInteractionHandlerSpec(),
                MusicHandler.getInteractionHandlerSpec(),
                ChatHandler.getInteractionHandlerSpec())).run();
    }
}
