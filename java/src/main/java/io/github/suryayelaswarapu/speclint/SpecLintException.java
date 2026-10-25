package io.github.suryayelaswarapu.speclint;

/** Base class for every expected, user-facing error. The CLI maps these to exit code 2. */
public class SpecLintException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SpecLintException(String message) {
        super(message);
    }

    public SpecLintException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The input file could not be read or parsed. */
    public static final class InputException extends SpecLintException {
        private static final long serialVersionUID = 1L;

        public InputException(String message) {
            super(message);
        }

        public InputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The input is a format or version spec-lint does not handle. */
    public static final class UnsupportedSpecException extends SpecLintException {
        private static final long serialVersionUID = 1L;

        public UnsupportedSpecException(String message) {
            super(message);
        }
    }

    /** The input tried something unsafe (entity expansion, path traversal, remote fetch...). */
    public static final class SecurityException extends SpecLintException {
        private static final long serialVersionUID = 1L;

        public SecurityException(String message) {
            super(message);
        }

        public SecurityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The .speclint.yaml configuration is invalid. */
    public static final class ConfigException extends SpecLintException {
        private static final long serialVersionUID = 1L;

        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
