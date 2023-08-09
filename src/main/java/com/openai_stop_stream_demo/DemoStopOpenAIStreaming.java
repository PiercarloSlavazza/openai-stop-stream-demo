package com.openai_stop_stream_demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;
import com.theokanning.openai.client.OpenAiApi;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Retrofit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.theokanning.openai.service.OpenAiService.*;
import static java.lang.String.format;
import static java.time.Duration.ofSeconds;

interface DemoStopOpenAIStreamingConfig {

    @Option
    String getOpenAiApiKey();

}

public class DemoStopOpenAIStreaming {

    private final static Logger log = LoggerFactory.getLogger(DemoStopOpenAIStreaming.class);
    private static final String MODEL = "gpt-3.5-turbo";

    public static OpenAiService buildOpenAiService(String apiKey) {
        ObjectMapper mapper = defaultObjectMapper();
        OkHttpClient client = defaultClient(apiKey, ofSeconds(120));
        Retrofit retrofit = defaultRetrofit(client, mapper);
        OpenAiApi openAiApi = retrofit.create(OpenAiApi.class);

        return new OpenAiService(openAiApi);
    }

    public static void main(String[] args) {
        DemoStopOpenAIStreamingConfig config = CliFactory.parseArguments(DemoStopOpenAIStreamingConfig.class, args);
        log.info("start");

        OpenAiService service = buildOpenAiService(config.getOpenAiApiKey());

        final List<ChatMessage> messages = new ArrayList<>() {{
            add(new ChatMessage(ChatMessageRole.USER.value(), "Summarize the Gettysburg Address in 50 words"));
        }};
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model(MODEL)
                .messages(messages)
                .temperature(0.1d)
                .n(1)
                .logitBias(new HashMap<>())
                .build();

        System.out.println();
        AtomicInteger chunksReceived = new AtomicInteger(0);
        service.streamChatCompletion(chatCompletionRequest)
                .doOnError(Throwable::printStackTrace)
                .blockingForEach(chatCompletionChunk -> {
                    String responseChunk = chatCompletionChunk.getChoices().stream().map(choice -> choice.getMessage().getContent()).collect(Collectors.joining());
                    if (StringUtils.isEmpty(responseChunk) || "null".equals(responseChunk)) return;

                    chunksReceived.incrementAndGet();
                    System.out.print(responseChunk);
                });
        System.out.println();

        log.info(format("end|chunks received|%d", chunksReceived.get()));
    }

}
