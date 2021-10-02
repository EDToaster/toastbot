package ca.edtoaster.partition;

import ca.edtoaster.commands.InteractionHandler;
import ca.edtoaster.commands.data.SlashInteractionData;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.rest.util.ApplicationCommandOptionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Log4j2
@RequiredArgsConstructor
public class PartitionedCommandHandler {
    private final String name;
    private final List<ApplicationCommandOptionData> options;
    private final Method handlerMethod;
    private final InteractionHandler handlerInstance;

    private static final Map<Integer, Function<ApplicationCommandInteractionOptionValue, Object>> fetcher;
    static {
        fetcher = new HashMap<>();
        fetcher.put(ApplicationCommandOptionType.STRING.getValue(), ApplicationCommandInteractionOptionValue::asString);
        fetcher.put(ApplicationCommandOptionType.INTEGER.getValue(), ApplicationCommandInteractionOptionValue::asLong);
        fetcher.put(ApplicationCommandOptionType.BOOLEAN.getValue(), ApplicationCommandInteractionOptionValue::asBoolean);
        fetcher.put(ApplicationCommandOptionType.USER.getValue(), v -> v.asUser().block());
        fetcher.put(ApplicationCommandOptionType.CHANNEL.getValue(), v -> v.asChannel().block());
        fetcher.put(ApplicationCommandOptionType.ROLE.getValue(), v -> v.asRole().block());
    }

    private Object fetchOptions(SlashCommandEvent event, ApplicationCommandOptionData data) {
        Optional<ApplicationCommandInteractionOption> optOpt = event.getOption(data.name());
        if (optOpt.isEmpty()) return null;
        Optional<ApplicationCommandInteractionOptionValue> valOpt = optOpt.get().getValue();
        if (valOpt.isEmpty()) return null;

        ApplicationCommandInteractionOptionValue val = valOpt.get();
        if (!fetcher.containsKey(data.type())) {
            throw new IllegalArgumentException(String.format("Value %s does not have an associated type", val.getRaw()));
        }

        return fetcher.get(data.type()).apply(val);
    }

    public Publisher<?> handle(SlashInteractionData interaction) {
        List<Object> methodParams =
                options.stream()
                    .map(data -> fetchOptions(interaction.getEvent(), data))
                    .collect(Collectors.toList());

        methodParams.add(0, interaction);

        try {
            return (Publisher<?>) handlerMethod.invoke(handlerInstance, methodParams.toArray());
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e);
            throw new RuntimeException(e);
        }
    }
}
