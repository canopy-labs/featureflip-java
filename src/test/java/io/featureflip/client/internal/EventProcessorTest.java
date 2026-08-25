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

    @Test
    void requeueReturnsEventsToTheFrontOfTheQueue() {
        // A batch that failed to send, plus an event recorded while it was in flight.
        EventProcessor processor = new EventProcessor(100);
        processor.enqueue(makeEvent("flag-3"));

        int dropped = processor.requeue(List.of(makeEvent("flag-1"), makeEvent("flag-2")));

        // The retried batch leads, so rough chronological order survives the round trip.
        assertThat(dropped).isZero();
        assertThat(processor.drain())
            .extracting(SdkEvent::getFlagKey)
            .containsExactly("flag-1", "flag-2", "flag-3");
    }

    @Test
    void requeueOfAnEmptyBatchIsANoOp() {
        EventProcessor processor = new EventProcessor(100);

        assertThat(processor.requeue(List.of())).isZero();
        assertThat(processor.requeue(null)).isZero();
        assertThat(processor.drain()).isEmpty();
    }

    @Test
    void enqueueBeyondTheBoundShedsTheOldestEvents() {
        // The bound used to shed the NEWEST event, which would have discarded a re-queued
        // batch the moment it came back. Oldest-first also matches the other SDKs.
        EventProcessor processor = new EventProcessor(100, 3);

        for (int i = 0; i < 5; i++) {
            processor.enqueue(makeEvent("flag-" + i));
        }

        assertThat(processor.drain())
            .extracting(SdkEvent::getFlagKey)
            .containsExactly("flag-2", "flag-3", "flag-4");
    }

    @Test
    void requeueBeyondTheBoundShedsTheOldestAndReportsTheCount() {
        // Queue already full of newer events when a stale batch comes back. A sustained
        // outage must shed the stale batch rather than starve the fresh analytics.
        EventProcessor processor = new EventProcessor(100, 3);
        processor.enqueue(makeEvent("new-1"));
        processor.enqueue(makeEvent("new-2"));
        processor.enqueue(makeEvent("new-3"));

        int dropped = processor.requeue(List.of(makeEvent("old-1"), makeEvent("old-2")));

        assertThat(dropped).isEqualTo(2);
        assertThat(processor.drain())
            .extracting(SdkEvent::getFlagKey)
            .containsExactly("new-1", "new-2", "new-3");
    }

    @Test
    void requeueAfterCloseReportsTheWholeBatchAsDropped() {
        // Nothing will drain the queue after close, so the batch must not be buffered.
        EventProcessor processor = new EventProcessor(100);
        processor.close();

        assertThat(processor.requeue(List.of(makeEvent("a"), makeEvent("b")))).isEqualTo(2);
        assertThat(processor.drain()).isEmpty();
    }

    @Test
    void drainStillWorksAfterClose() {
        // Shutdown's one final flush attempt has to be able to read what is buffered.
        EventProcessor processor = new EventProcessor(100);
        processor.enqueue(makeEvent("flag-1"));
        processor.close();

        assertThat(processor.drain()).hasSize(1);
    }
}
