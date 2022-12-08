package ca.edtoaster.commands;

import ca.edtoaster.commands.data.MessageCreateData;
import reactor.core.publisher.Mono;

public interface MessageHandler {
    Mono<Void> handleMessageCreate(MessageCreateData data);
}
