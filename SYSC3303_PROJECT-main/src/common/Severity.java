package common;

import java.io.Serializable;

public enum Severity implements Serializable {
    LOW, MODERATE, HIGH;

    public int requiredAgentLitres() {
        return switch (this) {
            case LOW -> 10;
            case MODERATE -> 20;
            case HIGH -> 30;
        };
    }
}
