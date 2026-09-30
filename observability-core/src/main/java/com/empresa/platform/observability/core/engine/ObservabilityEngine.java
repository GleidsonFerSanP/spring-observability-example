package com.empresa.platform.observability.core.engine;

import com.empresa.platform.observability.core.flow.FlowDimensions;
import com.empresa.platform.observability.core.flow.FlowExecution;

/**
 * SPI Principal de Engine de Observabilidade (Hexagonal Architecture / Ports & Adapters).
 * Abstrai a criação de spans, telemetria de fluxo, passos, métricas e tags contextuais
 * desacoplando a camada de aplicação de backends de APM específicos.
 */
public interface ObservabilityEngine {

    /**
     * Retorna o perfil de capacidades suportadas por esta engine.
     */
    EngineCapabilities getCapabilities();

    /**
     * Inicia a medição de um fluxo de negócio de nível superior (@TrackFlow).
     */
    FlowScope startFlow(String flowName, String flowType, FlowDimensions dimensions);

    /**
     * Finaliza a execução do fluxo e publica as métricas agregadas.
     */
    void completeFlow(FlowExecution execution, FlowScope scope);

    /**
     * Registra a interrupção ou falha catastrófica de um fluxo.
     */
    void recordFlowInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, FlowScope scope);

    /**
     * Inicia a medição de um passo ou subprocesso de integração (@TrackStep).
     */
    StepScope startStep(String flowName, String stepName, String stepType, FlowDimensions dimensions);

    /**
     * Finaliza a execução de um passo com sucesso e computa sua duração.
     */
    void completeStep(String flowName, String stepName, String stepType, long durationNanos, FlowDimensions dimensions, StepScope scope);

    /**
     * Registra a falha de um passo de subprocesso.
     */
    void recordStepInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, StepScope scope);

    /**
     * Aplica uma tag contextual (baixa cardinalidade) na observação/span ativo.
     */
    void tagAttribute(String key, String value);
}
