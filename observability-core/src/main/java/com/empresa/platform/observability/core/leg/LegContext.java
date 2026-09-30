package com.empresa.platform.observability.core.leg;

import com.empresa.platform.observability.core.annotation.LegType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contexto de execução e hierarquia de Legs (Pernas) isolado por thread.
 */
public class LegContext {

    private static final ThreadLocal<State> CURRENT_STATE = ThreadLocal.withInitial(State::new);

    private static class State {
        final AtomicInteger sequence = new AtomicInteger(0);
        final Deque<LegSnapshot> stack = new ArrayDeque<>();
    }

    public record LegSnapshot(
            int legNumber,
            Integer parentLegNumber,
            String target,
            LegType type,
            long startNanos
    ) {}

    public static LegSnapshot startLeg(String target, LegType type) {
        State state = CURRENT_STATE.get();
        int nextNumber = state.sequence.incrementAndGet();
        Integer parentNumber = state.stack.isEmpty() ? null : state.stack.peek().legNumber();

        LegSnapshot snapshot = new LegSnapshot(nextNumber, parentNumber, target, type, System.nanoTime());
        state.stack.push(snapshot);
        return snapshot;
    }

    public static LegSnapshot currentLeg() {
        State state = CURRENT_STATE.get();
        return state.stack.peek();
    }

    public static LegSnapshot completeLeg() {
        State state = CURRENT_STATE.get();
        if (state.stack.isEmpty()) {
            return null;
        }
        LegSnapshot completed = state.stack.pop();
        if (state.stack.isEmpty()) {
            CURRENT_STATE.remove();
        }
        return completed;
    }

    public static void clear() {
        CURRENT_STATE.remove();
    }
}
