package doc

import (
	"fmt"
	"math/big"
	"strconv"
)

// AsMap returns v as *Map, or nil (a safe, empty map) when it is not one.
func AsMap(v any) *Map {
	m, _ := v.(*Map)
	return m
}

// IsMap reports whether v is a non-nil *Map.
func IsMap(v any) bool {
	m, ok := v.(*Map)
	return ok && m != nil
}

// AsList returns v as []any, or nil when it is not a list.
func AsList(v any) []any {
	l, _ := v.([]any)
	return l
}

// IsList reports whether v is a list.
func IsList(v any) bool {
	_, ok := v.([]any)
	return ok
}

// Truthy implements Python-style truthiness: nil, false, 0, "" and empty collections are false.
func Truthy(v any) bool {
	switch x := v.(type) {
	case nil:
		return false
	case bool:
		return x
	case string:
		return x != ""
	case *Map:
		return x.Len() > 0
	case []any:
		return len(x) > 0
	case int64:
		return x != 0
	case int:
		return x != 0
	case float64:
		return x != 0
	}
	return true
}

// Str formats a scalar for messages; nil becomes "".
func Str(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case *big.Int:
		return x.String()
	}
	return fmt.Sprint(v)
}

// DeepCopy copies maps and lists; scalars are shared.
func DeepCopy(v any) any {
	switch x := v.(type) {
	case *Map:
		if x == nil {
			return (*Map)(nil)
		}
		out := NewMap()
		for _, k := range x.keys {
			out.Set(k, DeepCopy(x.vals[k]))
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = DeepCopy(e)
		}
		return out
	}
	return v
}

// CopyMap deep-copies a map (nil becomes an empty map).
func CopyMap(m *Map) *Map {
	if m == nil {
		return NewMap()
	}
	return DeepCopy(m).(*Map)
}

// Child returns the map at key, creating (and storing) one when missing or not a map.
func Child(parent *Map, key string) *Map {
	if m := AsMap(parent.Get(key)); m != nil {
		return m
	}
	m := NewMap()
	parent.Set(key, m)
	return m
}

// Contains reports whether list holds a value equal to v (scalars only).
func Contains(list []any, v any) bool {
	for _, e := range list {
		if e == v {
			return true
		}
	}
	return false
}

// ToPlain converts to plain Go values (map[string]any, float64 numbers) for comparisons.
func ToPlain(v any) any {
	switch x := v.(type) {
	case *Map:
		if x == nil {
			return nil
		}
		out := map[string]any{}
		for _, k := range x.keys {
			out[k] = ToPlain(x.vals[k])
		}
		return out
	case map[string]any:
		out := map[string]any{}
		for k, e := range x {
			out[k] = ToPlain(e)
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = ToPlain(e)
		}
		return out
	case int64:
		return float64(x)
	case int:
		return float64(x)
	case *big.Int:
		f, _ := new(big.Float).SetInt(x).Float64()
		return f
	}
	return v
}
