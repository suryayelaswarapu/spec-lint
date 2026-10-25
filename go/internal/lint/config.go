package lint

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// ConfigFilenames are searched in the working directory.
var ConfigFilenames = []string{".speclint.yaml", ".speclint.yml"}

// FailOnValues are the accepted fail-on thresholds.
var FailOnValues = []string{"error", "warning", "info", "never"}

// Config is the lint configuration from .speclint.yaml.
type Config struct {
	Extends   string
	FailOn    string
	Overrides map[string]Severity
}

// DefaultConfig is the recommended ruleset, failing on errors.
func DefaultConfig() *Config {
	return &Config{Extends: "recommended", FailOn: "error", Overrides: map[string]Severity{}}
}

// Severity is the effective severity of a rule.
func (c *Config) Severity(id string) Severity {
	if s, ok := c.Overrides[id]; ok {
		return s
	}
	r, _ := RuleByID(id)
	if c.Extends == "strict" {
		switch r.Severity {
		case SevWarning:
			return SevError
		case SevInfo:
			return SevWarning
		}
	}
	return r.Severity
}

// Enabled reports whether a rule is on.
func (c *Config) Enabled(id string) bool { return c.Severity(id) != SevOff }

// Fails reports whether a finding of this severity should fail the command.
func (c *Config) Fails(s Severity) bool {
	if c.FailOn == "never" {
		return false
	}
	return s.Rank() >= Severity(c.FailOn).Rank()
}

func contains(list []string, v string) bool {
	for _, e := range list {
		if e == v {
			return true
		}
	}
	return false
}

// LoadConfig loads path, else .speclint.yaml in searchDir (or cwd), else defaults.
func LoadConfig(path, searchDir string) (*Config, error) {
	if path == "" {
		dir := searchDir
		if dir == "" {
			dir, _ = os.Getwd()
		}
		for _, name := range ConfigFilenames {
			candidate := filepath.Join(dir, name)
			if info, err := os.Stat(candidate); err == nil && info.Mode().IsRegular() {
				path = candidate
				break
			}
		}
	}
	if path == "" {
		return DefaultConfig(), nil
	}
	src, err := specio.ReadSource(path, 256*1024)
	var data *doc.Map
	if err == nil {
		data, err = specio.ParseStructured(src.Text, path)
	}
	if err != nil {
		return nil, doc.Errorf(doc.ErrConfig, "Cannot read config %s: %v", path, err)
	}
	return ConfigFromMap(data)
}

// ConfigFromMap builds a config from a parsed .speclint.yaml (or an API request's config).
func ConfigFromMap(data *doc.Map) (*Config, error) {
	cfg := DefaultConfig()
	if data.Has("extends") {
		cfg.Extends = doc.Str(data.Get("extends"))
	}
	if cfg.Extends != "recommended" && cfg.Extends != "strict" {
		return nil, doc.Errorf(doc.ErrConfig, "extends must be 'recommended' or 'strict', not '%s'", cfg.Extends)
	}
	if data.Has("fail-on") {
		cfg.FailOn = doc.Str(data.Get("fail-on"))
	}
	if !contains(FailOnValues, cfg.FailOn) {
		return nil, doc.Errorf(doc.ErrConfig, "fail-on must be one of %s", strings.Join(FailOnValues, ", "))
	}
	raw := data.Get("rules")
	if raw != nil && !doc.IsMap(raw) {
		return nil, doc.Errorf(doc.ErrConfig, "rules must be a mapping of rule-id: severity")
	}
	for _, id := range doc.AsMap(raw).Keys() {
		if _, ok := RuleByID(id); !ok {
			return nil, doc.Errorf(doc.ErrConfig, "Unknown rule '%s' (run 'speclint rules' to list them)", id)
		}
		v := doc.AsMap(raw).Get(id)
		level := doc.Str(v)
		if b, ok := v.(bool); ok && !b {
			level = "off" // YAML 1.1 style `off`
		}
		sev, ok := ParseSeverity(level)
		if !ok {
			return nil, doc.Errorf(doc.ErrConfig, "Rule '%s': invalid severity '%s'", id, doc.Str(v))
		}
		cfg.Overrides[id] = sev
	}
	return cfg, nil
}
