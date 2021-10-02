package ca.edtoaster.commands;

import lombok.Data;

@Data
public class InteractionHandlerSpec {
    private final Class<? extends InteractionHandler> clazz;
    private final InteractionHandlerFactory factory;
}
