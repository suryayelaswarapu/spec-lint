package doc

import (
	"encoding/json"
	"math/big"
	"testing"
)

func TestNaming(t *testing.T) {
	cases := [][4]string{
		{"GetForecast", "getForecast", "GetForecast", "get-forecast"},
		{"create_alert_subscription", "createAlertSubscription", "CreateAlertSubscription", "create-alert-subscription"},
		{"NDFDgenByDay", "ndfDgenByDay", "NDFDgenByDay", "ndf-dgen-by-day"},
		{"forecast-v2", "forecastV2", "ForecastV2", "forecast-v2"},
	}
	for _, c := range cases {
		if ToCamel(c[0]) != c[1] || ToPascal(c[0]) != c[2] || ToKebab(c[0]) != c[3] {
			t.Errorf("%s -> %s %s %s", c[0], ToCamel(c[0]), ToPascal(c[0]), ToKebab(c[0]))
		}
	}
	if len(Words("--")) != 0 || ToCamel("--") != "--" || ToPascal("--") != "--" || ToKebab("--") != "--" {
		t.Error("empty words")
	}
	if got := Unique("a", InSet(map[string]bool{"a": true, "a2": true})); got != "a3" {
		t.Error(got)
	}
	if capFirst("") != "" {
		t.Error("capFirst")
	}
}

func TestMap(t *testing.T) {
	m := M("b", 1, "a", 2)
	m.Set("b", 3)
	if m.Keys()[0] != "b" || m.Get("b") != 3 || m.Len() != 2 {
		t.Fatal(m.Keys())
	}
	if m.SetDefault("a", 9) != 2 || m.SetDefault("c", 4) != 4 {
		t.Error("SetDefault")
	}
	if m.Delete("a") != 2 || m.Delete("zz") != nil || m.Has("a") {
		t.Error("Delete")
	}
	var nilMap *Map
	if nilMap.Len() != 0 || nilMap.Keys() != nil || nilMap.Has("x") || nilMap.Get("x") != nil || nilMap.Delete("x") != nil {
		t.Error("nil map")
	}
	nilMap.Each(func(string, any) { t.Error("nil each") })
	out, err := M("x", "<&>", "y", []any{int64(1)}, "z", (*Map)(nil)).MarshalJSON()
	if err != nil || string(out) != `{"x":"<&>","y":[1],"z":null}` {
		t.Errorf("%s %v", out, err)
	}
	if _, err := json.Marshal(M("bad", func() {})); err == nil {
		t.Error("expected marshal error")
	}
	if _, err := (*Map)(nil).MarshalJSON(); err != nil {
		t.Error(err)
	}
}

func TestValues(t *testing.T) {
	for _, v := range []any{nil, false, "", int64(0), 0, 0.0, NewMap(), []any{}} {
		if Truthy(v) {
			t.Errorf("%v should be falsy", v)
		}
	}
	for _, v := range []any{true, "x", int64(1), 2, 1.5, M("a", 1), []any{1}, struct{}{}} {
		if !Truthy(v) {
			t.Errorf("%v should be truthy", v)
		}
	}
	if Str(nil) != "" || Str(1.5) != "1.5" || Str(big.NewInt(7)) != "7" || Str(true) != "true" {
		t.Error("Str")
	}
	if AsMap("x") != nil || AsList("x") != nil || IsMap((*Map)(nil)) || !IsList([]any{}) {
		t.Error("As*")
	}
	orig := M("a", []any{M("b", 1)})
	cp := CopyMap(orig)
	AsMap(AsList(cp.Get("a"))[0]).Set("b", 2)
	if AsMap(AsList(orig.Get("a"))[0]).Get("b") != 1 {
		t.Error("deep copy shares")
	}
	if CopyMap(nil).Len() != 0 || DeepCopy((*Map)(nil)) != (*Map)(nil) {
		t.Error("copy nil")
	}
	parent := M("k", "not a map")
	Child(parent, "k").Set("x", 1)
	if !AsMap(parent.Get("k")).Has("x") || Child(parent, "k") != AsMap(parent.Get("k")) {
		t.Error("Child")
	}
	plain := ToPlain(M("i", int64(2), "n", 3, "b", big.NewInt(5), "m", map[string]any{"x": int64(1)}, "nil", (*Map)(nil)))
	pm := plain.(map[string]any)
	if pm["i"] != 2.0 || pm["n"] != 3.0 || pm["b"] != 5.0 || pm["m"].(map[string]any)["x"] != 1.0 || pm["nil"] != nil {
		t.Error(plain)
	}
	if !Contains([]any{"a"}, "a") || Contains([]any{"a"}, "b") {
		t.Error("Contains")
	}
	err := Errorf(ErrSecurity, "x %d", 1)
	if err.Error() != "x 1" || !IsKind(err, ErrSecurity) || IsKind(err, ErrInput) || IsKind(nil, ErrInput) {
		t.Error("errors")
	}
}
