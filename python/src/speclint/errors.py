"""Exception hierarchy. Every error raised on purpose derives from :class:`SpecLintError`."""


class SpecLintError(Exception):
    """Base class for all expected, user-facing errors."""


class InputError(SpecLintError):
    """The input file could not be read or parsed."""


class UnsupportedSpecError(SpecLintError):
    """The input is a format/version spec-lint does not handle."""


class SecurityError(SpecLintError):
    """The input tried something unsafe (entity expansion, path traversal, remote fetch...)."""


class ConfigError(SpecLintError):
    """The ``.speclint.yaml`` configuration is invalid."""
