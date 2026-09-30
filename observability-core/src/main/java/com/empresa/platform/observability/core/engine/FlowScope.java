package com.empresa.platform.observability.core.engine;

/**
 * Escopo ativo de execução de um Flow na Engine de Observabilidade.
 */
public interface FlowScope extends AutoCloseable {

    /**
     * Adiciona uma tag ao escopo do fluxo.
     */
    void tag(String key, String value);

    /**
     * Marca o fluxo como concluído com sucesso.
     */
    void markSuccess();

    /**
     * Marca o fluxo como degradado com fallback executado.
     */
    void markDegraded(String failedStep);

    /**
     * Marca o fluxo como interrompido por exceção.
     */
    void markInterrupted(String failedStep, Throwable error);

    @Override
    void close();
}
