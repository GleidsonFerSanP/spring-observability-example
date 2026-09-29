package com.gleidsonfersanp.observability.database;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    @Query(value = "SELECT * FROM transactions", nativeQuery = true)
    List<TransactionEntity> findAllTransactionsNative();
    
    // Using sleep function in H2 for simulating slow queries
    @Query(value = "SELECT * FROM transactions WHERE amount > ?1 AND 0 = DATEDIFF('SECOND', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) + SLEEP(?2)", nativeQuery = true)
    List<TransactionEntity> findTransactionsWithDelay(Double minAmount, int secondsToSleep);
}
