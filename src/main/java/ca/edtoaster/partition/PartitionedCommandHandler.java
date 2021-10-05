package ca.edtoaster.partition;

import ca.edtoaster.commands.data.SlashInteractionData;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.rest.util.ApplicationCommandOptionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handles multiple commands in the same @Namespace
 */
@Log4j2
@RequiredArgsConstructor
public class PartitionedCommandHandler {
    private final String command;
    private final Map<String, List<ApplicationCommandOptionData>> optionsMap;
    private final Map<String, Method> handlersMap;
    private final Object handlerInstance;

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

    private Object fetchOptions(ApplicationCommandInteractionOption subCommand, ApplicationCommandOptionData data) {
        Optional<ApplicationCommandInteractionOption> optOpt = subCommand.getOption(data.name());
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
        // assume there is a subcommand
        SlashCommandEvent event = interaction.getEvent();
        List<ApplicationCommandInteractionOption> options = event.getOptions();
        if (options.size() == 0) {
            // no subcommand -- throw??
            return event.replyEphemeral("Command needs subcommand");
        }

        ApplicationCommandInteractionOption subCommand = options.get(0);
        if (subCommand.getType() != ApplicationCommandOptionType.SUB_COMMAND) {
            // first option needs to be subcommand
            return event.replyEphemeral("First option needs to be subcommand");
        }

        String subCommandName = subCommand.getName();

        List<Object> methodParams =
                optionsMap.get(subCommandName).stream()
                    .map(data -> fetchOptions(subCommand, data))
                    .collect(Collectors.toList());

        methodParams.add(0, interaction);

        try {
            return (Publisher<?>) handlersMap.get(subCommandName).invoke(handlerInstance, methodParams.toArray());
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e);
            throw new RuntimeException(e);
        }
    }
}
