package org.openphc.cce.compliance.fhir;

public class UnsupportedExpressionLanguageException extends RuntimeException {

    private final String language;

    public UnsupportedExpressionLanguageException(String language) {
        super("Unsupported expression language: " + language);
        this.language = language;
    }

    public String getLanguage() {
        return language;
    }
}
