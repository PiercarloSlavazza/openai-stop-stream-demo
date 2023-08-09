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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.theokanning.openai.service.OpenAiService.*;
import static java.lang.String.format;
import static java.time.Duration.ofSeconds;

interface DemoStopOpenAIStreamingConfig {

    @Option
    String getOpenAiApiKey();

    @Option
    boolean getRetrofit();

    @Option
    Integer getMaxChunks();

    @SuppressWarnings("unused")
    boolean isMaxChunks();
}

class StreamingInterruptedByClientException extends RuntimeException {
}

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
class OpenAiChatStreamer {

    private final static Logger log = LoggerFactory.getLogger(OpenAiChatStreamer.class);

    private final String requestMessage;
    private final Optional<Consumer<String>> chunkConsumer;
    private final Optional<Integer> maxChunks;
    private final OpenAiService service;
    private final String model;

    OpenAiChatStreamer(String requestMessage, Optional<Integer> maxChunks, OpenAiService service, String model) {
        this(requestMessage, Optional.empty(), maxChunks, service, model);
    }

    OpenAiChatStreamer(String requestMessage, Optional<Consumer<String>> chunkConsumer, Optional<Integer> maxChunks, OpenAiService service, String model) {
        this.requestMessage = requestMessage;
        this.chunkConsumer = chunkConsumer;
        this.maxChunks = maxChunks;
        this.service = service;
        this.model = model;
    }

    public void stream() {
        final List<ChatMessage> messages = new ArrayList<>() {{
            add(new ChatMessage(ChatMessageRole.USER.value(), requestMessage));
        }};
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
                .builder()
                .model(model)
                .messages(messages)
                .temperature(0.1d)
                .n(1)
                .logitBias(new HashMap<>())
                .build();

        AtomicInteger chunksReceived = new AtomicInteger(0);
        log.info(format("streaming|start|%s", requestMessage));
        StringBuffer responseBuffer = new StringBuffer();
        boolean streamingHasBeenInterrupted = false;
        try {
            service.streamChatCompletion(chatCompletionRequest)
                    .doOnError(Throwable::printStackTrace)
                    .blockingForEach(chatCompletionChunk -> {
                        String responseChunk = chatCompletionChunk.getChoices().stream().map(choice -> choice.getMessage().getContent()).collect(Collectors.joining());
                        if (StringUtils.isEmpty(responseChunk) || "null".equals(responseChunk)) return;

                        chunksReceived.incrementAndGet();
                        responseBuffer.append(responseChunk);
                        chunkConsumer.ifPresent(stringConsumer -> stringConsumer.accept(responseChunk));

                        if (maxChunks.map(_maxChunks -> chunksReceived.get() == _maxChunks).orElse(false))
                            throw new StreamingInterruptedByClientException();
                    });
        } catch (StreamingInterruptedByClientException ignored) {
            streamingHasBeenInterrupted = true;
        }
        log.info(format("streaming|end|chunks|%d|interrupted|%b|%s|\n\t%s", chunksReceived.get(), streamingHasBeenInterrupted, requestMessage, responseBuffer));
    }
}

public class DemoStopOpenAIStreaming {

    private final static Logger log = LoggerFactory.getLogger(DemoStopOpenAIStreaming.class);
    private static final String MODEL = "gpt-3.5-turbo";

    public static OpenAiService buildOpenAiServiceWithRetrofit(String apiKey, Duration httpClientTimeout) {
        ObjectMapper mapper = defaultObjectMapper();
        OkHttpClient client = defaultClient(apiKey, httpClientTimeout);
        Retrofit retrofit = defaultRetrofit(client, mapper);
        OpenAiApi openAiApi = retrofit.create(OpenAiApi.class);

        return new OpenAiService(openAiApi, client.dispatcher().executorService());
    }

    public static OpenAiService buildOpenAiServiceNoRetrofit(String apiKey, Duration httpClientTimeout) {
        return new OpenAiService(apiKey, httpClientTimeout);
    }

    private static void joinThreads(List<Thread> streamersThreads) {
        streamersThreads.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void startThreads(List<Thread> streamersThreads) {
        streamersThreads.forEach(Thread::start);
    }

    public static void main(String[] args) {
        DemoStopOpenAIStreamingConfig config = CliFactory.parseArguments(DemoStopOpenAIStreamingConfig.class, args);
        log.info(format("start|retrofit enabled|%b|max chunks|%d", config.getRetrofit(), config.isMaxChunks() ? config.getMaxChunks() : -1));

        Duration httpClientTimeout = ofSeconds(120);
        OpenAiService service = config.getRetrofit() ?
                buildOpenAiServiceWithRetrofit(config.getOpenAiApiKey(), httpClientTimeout) :
                buildOpenAiServiceNoRetrofit(config.getOpenAiApiKey(), httpClientTimeout);

        List<OpenAiChatStreamer> chatStreamers = Stream.of(
                        "Summarize the Gettysburg Address in 50 words",
                        "Summarize the I have a dream speech in 50 words",
                        "Summarize the Pericles's Funeral Oration in 50 words"
                ).
                map(requestMessage -> new OpenAiChatStreamer(requestMessage, Optional.ofNullable(config.getMaxChunks()), service, MODEL)).
                toList();

        List<Thread> streamersThreads = chatStreamers.
                stream().
                map(openAiChatStreamer -> new Thread(openAiChatStreamer::stream)).
                toList();
        startThreads(streamersThreads);
        joinThreads(streamersThreads);

        service.shutdownExecutor();
    }

}
