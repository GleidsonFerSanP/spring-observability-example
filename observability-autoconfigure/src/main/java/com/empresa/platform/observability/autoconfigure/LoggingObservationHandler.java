package com.empresa.platform.observability.autoconfigure;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger log = LoggerFactory.getLogger(LoggingObservationHandler.class);

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    @Override
    public void onStart(Observation.Context context) {
        log.info("Starting operation: {}", context.getName());
    }

    @Override
    public void onStop(Observation.Context context) {
        log.info("Finished operation: {}", context.getName());
    }

    @Override
    public void onError(Observation.Context context) {
        log.error("Error in operation: {}", context.getName(), context.getError());
    }
}
