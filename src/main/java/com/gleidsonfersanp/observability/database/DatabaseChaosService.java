package com.gleidsonfersanp.observability.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DatabaseChaosService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseChaosService.class);
    private final TransactionRepository repository;

    public DatabaseChaosService(TransactionRepository repository) {
        this.repository = repository;
    }

    public TransactionEntity createTransaction(Double amount) {
        TransactionEntity entity = new TransactionEntity("Tx " + UUID.randomUUID(), amount, LocalDateTime.now());
        log.info("Creating normal transaction in database");
        return repository.save(entity);
    }

    public List<TransactionEntity> getAllTransactions() {
        log.info("Fetching all transactions");
        return repository.findAll();
    }

    @Transactional
    public List<TransactionEntity> simulateSlowQuery(int delaySeconds) {
        log.info("Simulating slow database operation for {} seconds...", delaySeconds);
        // Force the transaction to get a connection by making a query first
        List<TransactionEntity> initial = repository.findAll();
        
        try {
            // Hold the connection for the specified time
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Slow query simulation interrupted", e);
        }
        
        // Make another query to ensure the connection was still active
        return repository.findAll();
    }
    
    @Transactional
    public void simulatePoolExhaustion(int holdTimeSeconds) {
        log.warn("Starting pool exhaustion simulation. Holding connection for {} seconds...", holdTimeSeconds);
        // 1. Execute query to acquire connection
        repository.count();
        
        // 2. Sleep while holding the connection open
        try {
            Thread.sleep(holdTimeSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.warn("Releasing connection after pool exhaustion simulation.");
    }
}
