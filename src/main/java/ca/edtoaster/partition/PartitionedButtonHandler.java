package ca.edtoaster.partition;

import ca.edtoaster.commands.InteractionHandler;
import ca.edtoaster.commands.data.ButtonInteractionData;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

@Log4j2
@RequiredArgsConstructor
public class PartitionedButtonHandler {
    @Getter
    private final String prefix;
    private final Method handlerMethod;
    private final InteractionHandler handlerInstance;

    public Publisher<?> handle(ButtonInteractionData interaction) {
        try {
            return ((Publisher<?>) handlerMethod.invoke(handlerInstance, interaction));
        } catch (Exception e) {
            return Mono.error(new RuntimeException(e));
        }
    }
}
