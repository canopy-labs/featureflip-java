package io.featureflip.client.internal;

import io.featureflip.client.internal.model.SdkEvent;
import io.featureflip.client.internal.model.SdkEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventProcessorTest {

    private SdkEvent makeEvent(String flagKey) {
        SdkEvent e = new SdkEvent();
        e.setType(SdkEventType.EVALUATION);
        e.setFlagKey(flagKey);
        e.setTimestamp(Instant.now());
        return e;
    }

    @Test
    void drainReturnsEmptyWhenNoEvents() {
        EventProcessor processor = new EventProcessor(100);
        assertThat(processor.drain()).isEmpty();
    }

    @Test
    void enqueueAndDrain() {
        EventProcessor processor = new EventProcessor(100);
        processor.enqueue(makeEvent("flag-1"));
        processor.enqueue(makeEvent("flag-2"));

        List<SdkEvent> events = processor.drain();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getFlagKey()).isEqualTo("flag-1");
        assertThat(events.get(1).getFlagKey()).isEqualTo("flag-2");
    }

    @Test
    void drainClearsQueue() {
        EventProcessor processor = new EventProcessor(100);
        processor.enqueue(makeEvent("flag-1"));
        processor.drain();
        assertThat(processor.drain()).isEmpty();
    }

    @Test
    void shouldFlushAtBatchSize() {
        EventProcessor processor = new EventProcessor(2);
        processor.enqueue(makeEvent("flag-1"));
        assertThat(processor.shouldFlush()).isFalse();
        processor.enqueue(makeEvent("flag-2"));
        assertThat(processor.shouldFlush()).isTrue();
    }

    @Test
    void closedProcessorRejectsEvents() {
        EventProcessor processor = new EventProcessor(100);
        processor.close();
        processor.enqueue(makeEvent("flag-1"));
        assertThat(processor.drain()).isEmpty();
    }
}
