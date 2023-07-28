package dev.openfeature.contrib.providers.flagd.grpc;

import com.google.protobuf.Value;
import dev.openfeature.contrib.providers.flagd.FlagdCache;
import dev.openfeature.flagd.grpc.Schema.EventStreamResponse;
import dev.openfeature.sdk.ProviderState;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Consumer;

/**
 * EventStreamObserver handles events emitted by flagd.
 */
@Slf4j
public class EventStreamObserver implements StreamObserver<EventStreamResponse> {
    private final Consumer<ProviderState> setState;
    private final Runnable reconnectEventStream;
    private final FlagdCache cache;

    private static final String configurationChange = "configuration_change";
    private static final String providerReady = "provider_ready";
    static final String flagsKey = "flags";

    public EventStreamObserver(FlagdCache cache, Consumer<ProviderState> setState, Runnable reconnectEventStream) {
        this.cache = cache;
        this.setState = setState;
        this.reconnectEventStream = reconnectEventStream;
    }

    @Override
    public void onNext(EventStreamResponse value) {
        switch (value.getType()) {
            case configurationChange:
                this.handleConfigurationChangeEvent(value);
                break;
            case providerReady:
                this.handleProviderReadyEvent();
                break;
            default:
                log.debug("unhandled event type {}", value.getType());
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("event stream", t);
        if (this.cache.getEnabled()) {
            this.cache.clear();
        }
        this.setState.accept(ProviderState.ERROR);
        this.reconnectEventStream.run();
    }

    @Override
    public void onCompleted() {
        if (this.cache.getEnabled()) {
            this.cache.clear();
        }
        this.setState.accept(ProviderState.ERROR);
    }

    private void handleConfigurationChangeEvent(EventStreamResponse value) {
        this.setState.accept(ProviderState.READY);
        if (!this.cache.getEnabled()) {
            return;
        }
        Map<String, Value> data = value.getData().getFieldsMap();
        Value flagsValue = data.get(flagsKey);
        if (flagsValue == null) {
            this.cache.clear();
            return;
        }

        Map<String, Value> flags = flagsValue.getStructValue().getFieldsMap();
        for (String flagKey : flags.keySet()) {
            this.cache.remove(flagKey);
        }
    }

    private void handleProviderReadyEvent() {
        this.setState.accept(ProviderState.READY);
        if (this.cache.getEnabled()) {
            this.cache.clear();
        }
    }
}
