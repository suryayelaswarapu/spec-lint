package doc

import (
	"bytes"
	"encoding/json"
)

// Map is an insertion-ordered JSON object. All methods are safe on a nil *Map (reads return
// zero values), which keeps rule code tolerant of malformed specs.
type Map struct {
	keys []string
	vals map[string]any
}

// NewMap returns an empty ordered map.
func NewMap() *Map { return &Map{vals: map[string]any{}} }

// M builds an ordered map from alternating key/value arguments.
func M(kv ...any) *Map {
	m := NewMap()
	for i := 0; i+1 < len(kv); i += 2 {
		m.Set(kv[i].(string), kv[i+1])
	}
	return m
}

// Len is the number of keys.
func (m *Map) Len() int {
	if m == nil {
		return 0
	}
	return len(m.keys)
}

// Keys returns the keys in insertion order (a copy).
func (m *Map) Keys() []string {
	if m == nil {
		return nil
	}
	return append([]string(nil), m.keys...)
}

// Has reports whether key is present.
func (m *Map) Has(key string) bool {
	if m == nil {
		return false
	}
	_, ok := m.vals[key]
	return ok
}

// Get returns the value for key (nil when absent).
func (m *Map) Get(key string) any {
	if m == nil {
		return nil
	}
	return m.vals[key]
}

// Set stores a value, keeping the original position when the key already exists.
func (m *Map) Set(key string, value any) {
	if _, ok := m.vals[key]; !ok {
		m.keys = append(m.keys, key)
	}
	m.vals[key] = value
}

// SetDefault stores value only when key is absent and returns the stored value.
func (m *Map) SetDefault(key string, value any) any {
	if !m.Has(key) {
		m.Set(key, value)
	}
	return m.vals[key]
}

// Delete removes key and returns its value.
func (m *Map) Delete(key string) any {
	if m == nil {
		return nil
	}
	v, ok := m.vals[key]
	if !ok {
		return nil
	}
	delete(m.vals, key)
	for i, k := range m.keys {
		if k == key {
			m.keys = append(m.keys[:i], m.keys[i+1:]...)
			break
		}
	}
	return v
}

// Each calls fn for every entry in order. Mutating values is fine; adding keys is not.
func (m *Map) Each(fn func(key string, value any)) {
	for _, k := range m.Keys() {
		fn(k, m.vals[k])
	}
}

// MarshalJSON writes keys in insertion order.
func (m *Map) MarshalJSON() ([]byte, error) {
	if m == nil {
		return []byte("null"), nil
	}
	var buf bytes.Buffer
	buf.WriteByte('{')
	for i, k := range m.keys {
		if i > 0 {
			buf.WriteByte(',')
		}
		kb, err := marshalNoEscape(k)
		if err != nil {
			return nil, err
		}
		buf.Write(kb)
		buf.WriteByte(':')
		vb, err := marshalNoEscape(m.vals[k])
		if err != nil {
			return nil, err
		}
		buf.Write(vb)
	}
	buf.WriteByte('}')
	return buf.Bytes(), nil
}

func marshalNoEscape(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}
