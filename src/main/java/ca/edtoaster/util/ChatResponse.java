package ca.edtoaster.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import reactor.util.annotation.Nullable;

@Data
@Log4j2
public class ChatResponse {
    private final ResponseId id;
    private final String message;

    /*
        {
            "message": {
                "id": "28c38d0f-ebcf-4ceb-bb6b-b9a950cbcce1",
                "role": "assistant",
                "user": null,
                "create_time": null,
                "update_time": null,
                "content": {
                    "content_type": "text",
                    "parts": ["I'm"]
                },
                "end_turn": null,
                "weight": 1.0,
                "metadata": {},
                "recipient": "all"
            },
            "conversation_id": "bd2e18aa-348b-4d3f-a29e-11133cf1142c",
            "error": null
        }
     */
    @Nullable
    public static ChatResponse parse(String data) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node;
        try {
            node = mapper.readTree(data);
        } catch (JsonProcessingException e) {
            log.error(e);
            return null;
        }

        if (node == null) return null;

        JsonNode conversationId = node.get("conversation_id");
        if (conversationId == null) return null;

        JsonNode message = node.get("message");
        if (message == null) return null;

        JsonNode id = message.get("id");
        if (id == null) return null;

        JsonNode content = message.get("content");
        if (content == null) return null;

        JsonNode parts = content.get("parts");
        if (parts == null) return null;

        JsonNode part = parts.get(0);
        if (part == null) return null;

        return new ChatResponse(new ResponseId(conversationId.asText(), id.asText()), part.asText());
    }
}
