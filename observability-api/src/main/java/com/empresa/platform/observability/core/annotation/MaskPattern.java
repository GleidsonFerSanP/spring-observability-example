package com.empresa.platform.observability.core.annotation;

/**
 * Padrões de mascaramento para proteção de dados sensíveis (LGPD / PCI-DSS).
 */
public enum MaskPattern {
    PASSWORD {
        @Override
        public String apply(String value) {
            return "********";
        }
    },
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
    FULL_MASK {
        @Override
        public String apply(String value) {
            return "***REDACTED***";
        }
    };

    public abstract String apply(String value);
}
