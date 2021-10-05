package ca.edtoaster.commands;

import lombok.Data;

@Data
public class InteractionHandlerSpec {
    private final Class<?> clazz;
    private final InteractionHandlerFactory factory;
}
