package ca.edtoaster.util;

import discord4j.common.util.Snowflake;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collector;

@Log4j2
public class ChatGPT {

    public static final String CONVERSATION_ENDPOINT = "https://chat.openai.com/backend-api/conversation";

    /*
     * curl 'https://chat.openai.com/backend-api/conversation'
     *  -X POST
     *  -H <HEADERS>
     *  --data-raw '{"action":"next","messages":[{"id":"b6e943cd-140b-4dcd-a0da-e03062df049b","role":"user","content":{"content_type":"text","parts":["hello"]}}],"parent_message_id":"f3c5cc77-272a-4572-b994-b7e71d0a939a","model":"text-davinci-002-render"}'
     */

    public Mono<String> query(ChatRequest request, String token) {
        return getSender(request, token)
                .responseSingle((res, content) -> content)
                .map(c -> {
                    String res = c.toString(Charset.defaultCharset());
                    log.info(res);
                    return res;
                })
                .map(this::extractMessageFromResponse);
    }

    private String extractMessageFromResponse(String response) {
        return response.lines()
                .filter(Predicate.not(String::isBlank))
                .map(s -> s.replaceFirst("data:", "").strip())
                .takeWhile(Predicate.not("[DONE]"::equals))
                .collect(lastCollector())
                .orElse("");
    }

    public static <T>
    Collector<T, AtomicReference<T>, Optional<T>> lastCollector() {
        return Collector.of(
                        AtomicReference::new, AtomicReference::set,
                        (a1, a2) -> a2,
                        a -> Optional.ofNullable(a.get()));
    }

    private HttpClient.ResponseReceiver<?> getSender(ChatRequest data, String token) {
        return HttpClient.create()
                .headers((h) -> setHeaders(h, token))
                .post()
                .uri(CONVERSATION_ENDPOINT)
                .send(ByteBufFlux.fromString(Mono.just(data.formatJSON())));
    }

    /**
     *      *  -H 'User-Agent: ...'
     *      *  -H 'Accept: text/event-stream'
     *      *  -H 'Accept-Language: en-US,en;q=0.5'
     *      *  -H 'Accept-Encoding: gzip, deflate, br'
     *      *  -H 'Referer: https://chat.openai.com/chat'
     *      *  -H 'Content-Type: application/json'
     *      *  -H 'X-OpenAI-Assistant-App-Id: '
     *      *  -H 'Authorization: Bearer ...
     *      *  -H 'Origin: https://chat.openai.com'
     *      *  -H 'Connection: keep-alive'
     *      *  -H 'Cookie: ...
     *      *  -H 'Sec-Fetch-Dest: empty'
     *      *  -H 'Sec-Fetch-Mode: cors'
     *      *  -H 'Sec-Fetch-Site: same-origin'
     *      *  -H 'TE: trailers'
     * @param headers
     */
    private void setHeaders(HttpHeaders headers, String token) {
        headers
                .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:102.0) Gecko/20100101 Firefox/102.0")
                .add("Accept", "application/json")
                .add("Content-Type", "application/json")
                .add("Authorization", String.format("Bearer %s", token));
    }

    public static String getIdFromSnowflake(Snowflake messageID) {
        return messageID.asString();
    }
}
