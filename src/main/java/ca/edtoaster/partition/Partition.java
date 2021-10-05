package ca.edtoaster.partition;

import ca.edtoaster.bot.ToastBot;
import ca.edtoaster.commands.InteractionHandler;
import ca.edtoaster.commands.InteractionHandlerSpec;
import ca.edtoaster.annotations.ButtonListener;
import ca.edtoaster.annotations.Command;
import ca.edtoaster.annotations.Option;
import ca.edtoaster.commands.data.ButtonInteractionData;
import ca.edtoaster.commands.data.SlashInteractionData;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractEvent;
import discord4j.core.event.domain.interaction.SlashCommandEvent;
import discord4j.core.object.entity.User;
import discord4j.discordjson.json.ApplicationCommandData;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.service.ApplicationService;
import discord4j.rest.util.ApplicationCommandOptionType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@RequiredArgsConstructor
public class Partition {
    @Getter
    private final Snowflake namespace;
    private final User botUser;
    private final DiscordClient discordClient;
    private final List<InteractionHandlerSpec> interactionHandlerSpecs;

    private List<ApplicationCommandRequest> commandRequests;
    private Map<String, PartitionedCommandHandler> commandHandlers;
    private List<PartitionedButtonHandler> buttonHandlers;

    private void slurpCommand(final Method method, InteractionHandler handlerInstance) {
        log.info("Method has command annotation, validating options");
        Command annotationInstance = method.getAnnotation(Command.class);
        String annotationName = annotationInstance.name();
        String methodName = method.getName();
        String commandName = annotationName.isEmpty() ? methodName : annotationName;
        String description = annotationInstance.description();

        Class<?>[] parameterTypes = method.getParameterTypes();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        if (parameterTypes.length < 1) {
            throw new IllegalArgumentException(String.format("Method %s does not have enough parameters", methodName));
        }

        // first type needs to be slash command event
        Class<?> slashCommandEvent = parameterTypes[0];
        if (slashCommandEvent != SlashInteractionData.class) {
            throw new IllegalArgumentException(String.format("The first parameter of method %s needs to be type SlashInteraction", methodName));
        }

        // take the rest of the parameters
        List<ApplicationCommandOptionData> options = new ArrayList<>();

        int numOptions = parameterTypes.length - 1;
        for (int i = 1; i < numOptions + 1; i++) {
            Annotation[] annotations = parameterAnnotations[i];
            Class<?> parameterType = parameterTypes[i];

            Option optionAnnotation = null;
            // grab first matching annotation
            for (Annotation a : annotations) {
                if (a.annotationType() == Option.class) {
                    optionAnnotation = (Option) a;
                    break;
                }
            }

            if (Objects.isNull(optionAnnotation)) {
                throw new IllegalArgumentException(String.format("Option %s does not have an Option annotation", parameterType.toString()));
            }

            String optionName = optionAnnotation.name();
            String optionDescription = optionAnnotation.description();
            boolean required = optionAnnotation.required();

            if (!ToastBot.typesMap.containsKey(parameterType)) {
                throw new IllegalArgumentException(String.format("Option %s has unsupported type", parameterType.toString()));
            }
            ApplicationCommandOptionType type = ToastBot.typesMap.get(parameterType);

            log.info(String.format("-> %s: %s (%s)", optionName, parameterType.toString(), optionDescription));
            options.add(ApplicationCommandOptionData.builder()
                    .name(optionName)
                    .description(optionDescription)
                    .type(type.getValue())
                    .required(required)
                    .build());
        }

        commandRequests.add(
                ApplicationCommandRequest.builder()
                        .name(commandName)
                        .description(description)
                        .options(options)
                        .build());

        commandHandlers.put(commandName, new PartitionedCommandHandler(commandName, options, method, handlerInstance));
    }

    private void slurpButtonHandler(final Method method, InteractionHandler handlerInstance) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != 1 || parameters[0] != ButtonInteractionData.class) {
            throw new IllegalArgumentException(String.format("Method %s needs to have exactly one parameter ButtonInteraction", method.getName()));
        }

        ButtonListener annotation = method.getAnnotation(ButtonListener.class);

        buttonHandlers.add(new PartitionedButtonHandler(annotation.prefix(), method, handlerInstance));
    }

    private void slurpMethod(final Method method, InteractionHandler handlerInstance) {
        log.info("Found method " + method.getName());
        if (method.isAnnotationPresent(Command.class)) {
            slurpCommand(method, handlerInstance);
        }

        if (method.isAnnotationPresent(ButtonListener.class)) {
            slurpButtonHandler(method, handlerInstance);
        }
    }

    private void setupRequests(InteractionHandlerSpec spec) {
        Class<?> handlerClassSupers = spec.getClazz();
        InteractionHandler handlerInstance = spec.getFactory().create(namespace, discordClient);

        while (handlerClassSupers != InteractionHandler.class) {
            log.info("Grabbing methods for class " + handlerClassSupers.toString());
            for (final Method method : handlerClassSupers.getDeclaredMethods()) {
                slurpMethod(method, handlerInstance);
            }
            handlerClassSupers = handlerClassSupers.getSuperclass();
        }
    }

    public Mono<Void> refreshGuild() {
        commandRequests = new ArrayList<>();
        commandHandlers = new HashMap<>();
        buttonHandlers = new ArrayList<>();

        this.interactionHandlerSpecs.forEach(this::setupRequests);

        ApplicationService service = discordClient.getApplicationService();

        long guildID = namespace.asLong();

        return discordClient.getApplicationId()
                .flatMapMany(appID -> service.bulkOverwriteGuildApplicationCommand(appID, guildID, commandRequests))
                .doOnNext(a -> log.info(String.format("[Guild %s] Created command %s", guildID, a.name())))
                .then();
    }

    public Publisher<?> handleButton(ButtonInteractEvent event) {
        User who = event.getInteraction().getUser();
        ButtonInteractionData interaction = new ButtonInteractionData(namespace, who, botUser, event);
        interaction.log(log::info);

        return Flux.concat(
                buttonHandlers.stream()
                        .filter(handler -> event.getCustomId().startsWith(handler.getPrefix()))
                        .map(b -> b.handle(interaction))
                        .collect(Collectors.toList()))
                .doOnError(Throwable::printStackTrace)
                .doOnError(log::fatal);
    }

    public Publisher<?> handleSlash(SlashCommandEvent event) {
        User who = event.getInteraction().getUser();
        SlashInteractionData interaction = new SlashInteractionData(namespace, who, botUser, event);
        interaction.log(log::info);

        String command = event.getCommandName();
        if (commandHandlers.containsKey(command)) {
            return Flux.concat(commandHandlers.get(command).handle(interaction)).doOnError(log::fatal);
        } else {
            return event.replyEphemeral("Something went wrong");
        }
    }
}
