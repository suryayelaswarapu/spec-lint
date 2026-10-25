// Package doc holds the untyped, insertion-ordered JSON data model shared by every spec-lint
// package, plus naming helpers and the error types the CLI maps to exit code 2.
package doc

import "fmt"

// ErrorKind classifies expected, user-facing errors.
type ErrorKind string

const (
	ErrInput       ErrorKind = "input"       // the input could not be read or parsed
	ErrUnsupported ErrorKind = "unsupported" // a format/version spec-lint does not handle
	ErrSecurity    ErrorKind = "security"    // the input tried something unsafe
	ErrConfig      ErrorKind = "config"      // .speclint.yaml is invalid
	ErrGeneral     ErrorKind = "general"     // anything else the user must fix
)

// Error is the single error type spec-lint raises on purpose.
type Error struct {
	Kind ErrorKind
	Msg  string
}

func (e *Error) Error() string { return e.Msg }

// Errorf builds an *Error of the given kind.
func Errorf(kind ErrorKind, format string, args ...any) *Error {
	return &Error{Kind: kind, Msg: fmt.Sprintf(format, args...)}
}

// IsKind reports whether err is a spec-lint *Error of the given kind.
func IsKind(err error, kind ErrorKind) bool {
	e, ok := err.(*Error)
	return ok && e.Kind == kind
}
