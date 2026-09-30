package com.empresa.platform.observability.core.engine;

/**
 * Escopo ativo de execução de um Step na Engine de Observabilidade.
 */
public interface StepScope extends AutoCloseable {

    /**
     * Adiciona uma tag ao escopo do subprocesso/passo.
     */
    void tag(String key, String value);

    /**
     * Marca o passo como falho.
     */
    void markFailed(Throwable error);

    @Override
    void close();
}
