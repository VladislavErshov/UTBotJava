package org.utbot.engine.overrides.collections;

import org.utbot.api.annotation.UtClassMock;
import org.utbot.engine.overrides.stream.UtStream;

import java.util.stream.Stream;

@UtClassMock(target = java.util.Collection.class, internalUsage = true)
public interface Collection<E> extends java.util.Collection<E> {
    @Override
    default Stream<E> stream() {
        return new UtStream<>(this);
    }

    @Override
    default Stream<E> parallelStream() {
        return stream();
    }
}
