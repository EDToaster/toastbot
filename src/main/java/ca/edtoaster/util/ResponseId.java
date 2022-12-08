package ca.edtoaster.util;

import lombok.Data;
import reactor.util.annotation.Nullable;

@Data
public class ResponseId {
    @Nullable
    private final String conversationId;
    private final String parentId;
}
