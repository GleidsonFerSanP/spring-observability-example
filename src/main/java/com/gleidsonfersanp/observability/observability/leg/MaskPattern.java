package com.gleidsonfersanp.observability.observability.leg;

/**
 * Padrões de mascaramento para proteção de dados sensíveis (LGPD / PCI-DSS).
 */
public enum MaskPattern {
    /**
     * Substituição completa por asteriscos para senhas e tokens.
     */
    PASSWORD {
        @Override
        public String apply(String value) {
            return "********";
        }
    },

    /**
     * Preserva apenas os 4 últimos dígitos do cartão de crédito.
     * Ex: 4111222233331234 -> ************1234
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
     * Mascara o miolo do documento/CPF preservando início e fim para conferência.
     * Ex: 123.456.789-00 -> 123.***.***-00
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
     * Mascara o nome do usuário no e-mail preservando domínio.
     * Ex: usuario@empresa.com -> u***o@empresa.com
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
     * Mascaramento genérico integral.
     */
    FULL_MASK {
        @Override
        public String apply(String value) {
            return "***REDACTED***";
        }
    };

    public abstract String apply(String value);
}
