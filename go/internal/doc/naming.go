package doc

import (
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

var (
	wordSplit = regexp.MustCompile(`[^A-Za-z0-9]+`)
	// CamelCase, PascalCase and KebabCase validate identifiers.
	CamelCase  = regexp.MustCompile(`^[a-z][a-zA-Z0-9]*$`)
	PascalCase = regexp.MustCompile(`^[A-Z][a-zA-Z0-9]*$`)
	KebabCase  = regexp.MustCompile(`^[a-z0-9]+(?:-[a-z0-9]+)*$`)
)

func isUpper(b byte) bool { return b >= 'A' && b <= 'Z' }
func isLower(b byte) bool { return b >= 'a' && b <= 'z' }
func isDigit(b byte) bool { return b >= '0' && b <= '9' }

// splitCamel splits at lower/digit->Upper and at Upper->UpperLower boundaries
// (Go's RE2 has no lookarounds, so this is done by hand).
func splitCamel(s string) []string {
	var parts []string
	start := 0
	for i := 1; i < len(s); i++ {
		prev, cur := s[i-1], s[i]
		boundary := (isLower(prev) || isDigit(prev)) && isUpper(cur)
		if !boundary && isUpper(prev) && isUpper(cur) && i+1 < len(s) && isLower(s[i+1]) {
			boundary = true
		}
		if boundary {
			parts = append(parts, s[start:i])
			start = i
		}
	}
	return append(parts, s[start:])
}

// Words splits "GetForecast_byDay-v2" into [Get Forecast by Day v2].
func Words(name string) []string {
	var out []string
	for _, chunk := range wordSplit.Split(name, -1) {
		if chunk == "" {
			continue
		}
		for _, p := range splitCamel(chunk) {
			if p != "" {
				out = append(out, p)
			}
		}
	}
	return out
}

func capFirst(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	r[0] = unicode.ToUpper(r[0])
	return string(r)
}

// ToCamel converts to camelCase.
func ToCamel(name string) string {
	parts := Words(name)
	if len(parts) == 0 {
		return name
	}
	var sb strings.Builder
	sb.WriteString(strings.ToLower(parts[0]))
	for _, p := range parts[1:] {
		sb.WriteString(capFirst(strings.ToLower(p)))
	}
	return sb.String()
}

// ToPascal converts to PascalCase, keeping existing capitals ("NDFDgen" stays).
func ToPascal(name string) string {
	var sb strings.Builder
	for _, p := range Words(name) {
		sb.WriteString(capFirst(p))
	}
	if sb.Len() == 0 {
		return name
	}
	return sb.String()
}

// ToKebab converts to kebab-case.
func ToKebab(name string) string {
	parts := Words(name)
	if len(parts) == 0 {
		return name
	}
	for i, p := range parts {
		parts[i] = strings.ToLower(p)
	}
	return strings.Join(parts, "-")
}

// Unique returns name, or name2, name3... whichever is not taken.
func Unique(name string, taken func(string) bool) string {
	if !taken(name) {
		return name
	}
	i := 2
	for taken(name + strconv.Itoa(i)) {
		i++
	}
	return name + strconv.Itoa(i)
}

// InSet adapts a set for Unique.
func InSet(set map[string]bool) func(string) bool {
	return func(s string) bool { return set[s] }
}

// InMap adapts an ordered map's keys for Unique.
func InMap(m *Map) func(string) bool { return m.Has }
