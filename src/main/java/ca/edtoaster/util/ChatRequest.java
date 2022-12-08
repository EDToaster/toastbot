package ca.edtoaster.util;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import reactor.util.annotation.Nullable;

import java.util.UUID;

@Data
public class ChatRequest {
    /*
        {
            "action":"next",
            "parent_message_id":"f3c5cc77-272a-4572-b994-b7e71d0a939a",
            "model":"text-davinci-002-render"
            "messages":[
                {
                    "id":"b6e943cd-140b-4dcd-a0da-e03062df049b",
                    "role":"user",
                    "content":{
                        "content_type":"text",
                        "parts":["hello"]
                    }
                }
            ],
        }
     */

    private final ResponseId parentResponse;
    private final String text;

    public String formatJSON() {
        ObjectNode node = new ObjectNode(JsonNodeFactory.instance)
                .put("action", "next")
                .put("parent_message_id", parentResponse.getParentId())
                .put("conversation_id", parentResponse.getConversationId())
                .put("model", "text-davinci-002-render");

        ArrayNode messages = node.putArray("messages");
        ObjectNode message = messages.addObject()
                .put("id", UUID.randomUUID().toString())
                .put("role", "user");

        ObjectNode content = message.putObject("content")
                .put("content_type", "text");

        ArrayNode parts = content.putArray("parts")
                .add(text);

        return node.toString();
    }

}
