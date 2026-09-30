package com.empresa.platform.observability.core.annotation;

/**
 * Padrões semânticos padronizados de mascaramento para proteção de dados sensíveis
 * em conformidade com as diretrizes da LGPD (Lei Geral de Proteção de Dados) e PCI-DSS.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see MaskField
 * @see LogLeg
 */
public enum MaskPattern {

    /**
     * Ofuscação total para credenciais, senhas e chaves de segurança (ex: {@code "********"}).
     */
    PASSWORD {
        @Override
        public String apply(String value) {
            return "********";
        }
    },

    /**
     * Mascaramento parcial para cartões de crédito/débito, preservando apenas os 4 últimos dígitos
     * em conformidade com o padrão PCI-DSS (ex: {@code "************1234"}).
     */
    CARD_PARTIAL {
        @Override
        public String apply(String value) {
            if (value == null || value.length() < 4) {
                return "************";
            }
            String last4 = value.substring(value.length() - 4);
            return "*".repeat(Math.max(0, value.length() - 4)) + last4;
        }
    },

    /**
     * Mascaramento parcial para CPF, preservando o início e os dígitos verificadores
     * para auditoria (ex: {@code "123.***.***-45"}).
     */
    CPF_PARTIAL {
        @Override
        public String apply(String value) {
            if (value == null || value.length() < 5) {
                return "***.***.***-**";
            }
            String cleaned = value.replaceAll("[^0-9]", "");
            if (cleaned.length() == 11) {
                return cleaned.substring(0, 3) + ".***.***-" + cleaned.substring(9);
            }
            return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
        }
    },

    /**
     * Mascaramento parcial para endereços de e-mail, preservando a primeira e última letra do usuário
     * e o domínio completo (ex: {@code "j***e@dominio.com"}).
     */
    EMAIL_PARTIAL {
        @Override
        public String apply(String value) {
            if (value == null || !value.contains("@")) {
                return "***@***";
            }
            int atIndex = value.indexOf('@');
            String user = value.substring(0, atIndex);
            String domain = value.substring(atIndex);
            if (user.length() <= 2) {
                return "*".repeat(user.length()) + domain;
            }
            return user.charAt(0) + "***" + user.charAt(user.length() - 1) + domain;
        }
    },

    /**
     * Redação total com substituição por {@code "***REDACTED***"}.
     */
    FULL_MASK {
        @Override
        public String apply(String value) {
            return "***REDACTED***";
        }
    };

    /**
     * Aplica o algoritmo de mascaramento sobre o valor original.
     *
     * @param value valor original em texto
     * @return valor mascarado
     */
    public abstract String apply(String value);
}
