package com.gleidsonfersanp.observability.domain;

import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

@Component
public class AuditLogRepository {
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    public void addLog(String message) {
        logs.add(message);
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }
}
