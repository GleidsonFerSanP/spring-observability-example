package com.gleidsonfersanp.observability.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/db-chaos")
public class DatabaseChaosController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseChaosController.class);
    private final DatabaseChaosService databaseChaosService;

    public DatabaseChaosController(DatabaseChaosService databaseChaosService) {
        this.databaseChaosService = databaseChaosService;
    }

    @PostMapping("/normal")
    public ResponseEntity<TransactionEntity> createNormalTransaction(@RequestParam(defaultValue = "100.0") Double amount) {
        return ResponseEntity.ok(databaseChaosService.createTransaction(amount));
    }

    @GetMapping("/normal")
    public ResponseEntity<List<TransactionEntity>> getAllTransactions() {
        return ResponseEntity.ok(databaseChaosService.getAllTransactions());
    }

    @GetMapping("/slow-query")
    public ResponseEntity<String> slowQuery(@RequestParam(defaultValue = "5") int delaySeconds) {
        databaseChaosService.simulateSlowQuery(delaySeconds);
        return ResponseEntity.ok("Slow query completed after " + delaySeconds + " seconds.");
    }

    @PostMapping("/exhaust-pool")
    public ResponseEntity<String> exhaustConnectionPool(
            @RequestParam(defaultValue = "10") int concurrentRequests,
            @RequestParam(defaultValue = "10") int holdTimeSeconds) {
            
        log.warn("Triggering connection pool exhaustion with {} concurrent requests...", concurrentRequests);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // Since our pool is small (max 5), sending 10 concurrent requests that hold connections 
        // for 10 seconds will definitely exhaust the pool and cause HikariCP to throw 
        // SQLTransientConnectionException for some of them.
        for (int i = 0; i < concurrentRequests; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    databaseChaosService.simulatePoolExhaustion(holdTimeSeconds);
                } catch (Exception e) {
                    log.error("Error during pool exhaustion task: {}", e.getMessage());
                }
            });
            futures.add(future);
        }
        
        return ResponseEntity.ok("Dispatched " + concurrentRequests + " background tasks to exhaust the connection pool. Check logs and metrics.");
    }
}
