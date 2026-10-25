// Package specio reads, parses and serialises spec documents safely.
//
// Files larger than the size limit are rejected before reading; YAML aliases are capped (to
// defeat "billion laughs" expansion); only standard YAML tags are accepted; timestamps stay
// strings so `version: 2024-01-01` round-trips unchanged.
package specio

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"math/big"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"gopkg.in/yaml.v3"
)

const (
	// DefaultMaxBytes is the default input size limit (10 MiB).
	DefaultMaxBytes int64 = 10 * 1024 * 1024
	// MaxYAMLAliases caps alias expansion.
	MaxYAMLAliases = 100
)

// SourceFile is a file read from disk.
type SourceFile struct {
	Path string
	Text string
}

// Name is the file's base name.
func (s SourceFile) Name() string { return filepath.Base(s.Path) }

// ReadSource reads a UTF-8 text file, enforcing a size limit.
func ReadSource(path string, maxBytes int64) (SourceFile, error) {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return SourceFile{}, doc.Errorf(doc.ErrInput, "File not found: %s", path)
	}
	if info.Size() > maxBytes {
		return SourceFile{}, doc.Errorf(doc.ErrSecurity,
			"%s is %d bytes; limit is %d (use --max-size)", filepath.Base(path), info.Size(), maxBytes)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return SourceFile{}, doc.Errorf(doc.ErrInput, "Cannot read %s: %v", path, err)
	}
	if !utf8.Valid(data) {
		return SourceFile{}, doc.Errorf(doc.ErrInput, "%s is not valid UTF-8", filepath.Base(path))
	}
	text := strings.TrimPrefix(string(data), "\uFEFF")
	return SourceFile{Path: path, Text: text}, nil
}

// ParseStructured parses JSON or YAML text into an ordered map.
func ParseStructured(text, name string) (*doc.Map, error) {
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(text), &root); err != nil {
		return nil, doc.Errorf(doc.ErrInput, "%s: could not parse as JSON/YAML: %v", name, err)
	}
	if root.Kind != yaml.DocumentNode || len(root.Content) == 0 {
		return nil, doc.Errorf(doc.ErrInput, "%s: top level must be a mapping/object", name)
	}
	c := &converter{name: name}
	v, err := c.value(root.Content[0], 0)
	if err != nil {
		return nil, err
	}
	m, ok := v.(*doc.Map)
	if !ok {
		return nil, doc.Errorf(doc.ErrInput, "%s: top level must be a mapping/object", name)
	}
	return m, nil
}

type converter struct {
	name    string
	aliases int
}

const maxNesting = 200

func (c *converter) value(n *yaml.Node, depth int) (any, error) {
	if depth > maxNesting {
		return nil, doc.Errorf(doc.ErrInput, "%s: nesting too deep", c.name)
	}
	switch n.Kind {
	case yaml.AliasNode:
		c.aliases++
		if c.aliases > MaxYAMLAliases {
			return nil, doc.Errorf(doc.ErrSecurity,
				"YAML document uses more than %d aliases; refusing to expand", MaxYAMLAliases)
		}
		return c.value(n.Alias, depth+1)
	case yaml.MappingNode:
		m := doc.NewMap()
		for i := 0; i+1 < len(n.Content); i += 2 {
			k, v := n.Content[i], n.Content[i+1]
			val, err := c.value(v, depth+1)
			if err != nil {
				return nil, err
			}
			m.Set(k.Value, val)
		}
		return m, nil
	case yaml.SequenceNode:
		out := make([]any, 0, len(n.Content))
		for _, e := range n.Content {
			val, err := c.value(e, depth+1)
			if err != nil {
				return nil, err
			}
			out = append(out, val)
		}
		return out, nil
	case yaml.ScalarNode:
		return c.scalar(n)
	}
	return nil, nil
}

func (c *converter) scalar(n *yaml.Node) (any, error) {
	switch n.ShortTag() {
	case "!!str", "!!timestamp", "!!binary":
		return n.Value, nil
	case "!!null":
		return nil, nil
	case "!!bool":
		var b bool
		if err := n.Decode(&b); err != nil {
			return nil, doc.Errorf(doc.ErrInput, "%s: %v", c.name, err)
		}
		return b, nil
	case "!!int":
		var i int64
		if err := n.Decode(&i); err == nil {
			return i, nil
		}
		if b, ok := new(big.Int).SetString(strings.ReplaceAll(n.Value, "_", ""), 0); ok {
			return b, nil
		}
		return n.Value, nil
	case "!!float":
		if b, ok := new(big.Int).SetString(n.Value, 10); ok {
			return b, nil // an integer too large for int64
		}
		var f float64
		if err := n.Decode(&f); err != nil {
			return nil, doc.Errorf(doc.ErrInput, "%s: %v", c.name, err)
		}
		return f, nil
	}
	return nil, doc.Errorf(doc.ErrInput, "%s: could not parse as JSON/YAML: unsupported tag %s",
		c.name, n.Tag)
}

// Dump serialises a document as "yaml" or "json", preserving key order.
func Dump(m *doc.Map, format string) (string, error) {
	switch format {
	case "json":
		var buf bytes.Buffer
		enc := json.NewEncoder(&buf)
		enc.SetEscapeHTML(false)
		enc.SetIndent("", "  ")
		if err := enc.Encode(m); err != nil {
			return "", err
		}
		return buf.String(), nil
	case "yaml":
		var buf bytes.Buffer
		enc := yaml.NewEncoder(&buf)
		enc.SetIndent(2)
		if err := enc.Encode(toNode(m)); err != nil {
			return "", err
		}
		_ = enc.Close()
		return buf.String(), nil
	}
	return "", fmt.Errorf("unknown output format: %s", format)
}

// MustDump is Dump for formats known to be valid.
func MustDump(m *doc.Map, format string) string {
	s, err := Dump(m, format)
	if err != nil {
		panic(err)
	}
	return s
}

func scalarNode(tag, value string) *yaml.Node {
	return &yaml.Node{Kind: yaml.ScalarNode, Tag: tag, Value: value}
}

func toNode(v any) *yaml.Node {
	switch x := v.(type) {
	case *doc.Map:
		n := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
		for _, k := range x.Keys() {
			n.Content = append(n.Content, scalarNode("!!str", k), toNode(x.Get(k)))
		}
		return n
	case []any:
		n := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		for _, e := range x {
			n.Content = append(n.Content, toNode(e))
		}
		return n
	case string:
		n := scalarNode("!!str", x)
		if strings.Contains(x, "\n") {
			n.Style = yaml.LiteralStyle
		}
		return n
	case bool:
		return scalarNode("!!bool", strconv.FormatBool(x))
	case int64:
		return scalarNode("!!int", strconv.FormatInt(x, 10))
	case int:
		return scalarNode("!!int", strconv.Itoa(x))
	case *big.Int:
		return scalarNode("!!int", x.String())
	case float64:
		if math.IsInf(x, 0) || math.IsNaN(x) {
			return scalarNode("!!float", map[bool]string{true: ".inf", false: ".nan"}[math.IsInf(x, 0)])
		}
		s := strconv.FormatFloat(x, 'g', -1, 64)
		if !strings.ContainsAny(s, ".eE") {
			s += ".0"
		}
		return scalarNode("!!float", s)
	case nil:
		return scalarNode("!!null", "null")
	}
	return scalarNode("!!str", fmt.Sprint(v))
}

// FormatForPath returns "json" for a .json path, otherwise "yaml".
func FormatForPath(path string) string {
	if strings.HasSuffix(strings.ToLower(path), ".json") {
		return "json"
	}
	return "yaml"
}

// LineIndex maps JSON-pointer-like paths (joined with "\x00") to 1-based line numbers.
// Best effort: returns an empty index when the text cannot be parsed.
func LineIndex(text string) map[string]int {
	index := map[string]int{}
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(text), &root); err != nil || len(root.Content) == 0 {
		return index
	}
	type item struct {
		node *yaml.Node
		path []string
	}
	stack := []item{{root.Content[0], nil}}
	for len(stack) > 0 {
		it := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		key := PathKey(it.path)
		if _, ok := index[key]; !ok {
			index[key] = it.node.Line
		}
		switch it.node.Kind {
		case yaml.MappingNode:
			for i := 0; i+1 < len(it.node.Content); i += 2 {
				child := append(append([]string(nil), it.path...), it.node.Content[i].Value)
				index[PathKey(child)] = it.node.Content[i].Line
				stack = append(stack, item{it.node.Content[i+1], child})
			}
		case yaml.SequenceNode:
			for i, e := range it.node.Content {
				child := append(append([]string(nil), it.path...), strconv.Itoa(i))
				stack = append(stack, item{e, child})
			}
		}
	}
	return index
}

// PathKey joins a path for use as a LineIndex key.
func PathKey(path []string) string { return strings.Join(path, "\x00") }

// ErrNotDoc is returned by helpers that need a document.
var ErrNotDoc = errors.New("not a document")
