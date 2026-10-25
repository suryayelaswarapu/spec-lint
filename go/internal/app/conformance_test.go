package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

func testdata(t *testing.T) string {
	t.Helper()
	dir, err := filepath.Abs("../../../testdata")
	if err != nil {
		t.Fatal(err)
	}
	return dir
}

// TestConformance checks this implementation against the shared golden files.
func TestConformance(t *testing.T) {
	files, _ := filepath.Glob(filepath.Join(testdata(t), "conformance", "*.json"))
	if len(files) == 0 {
		t.Fatal("no conformance files")
	}
	for _, file := range files {
		t.Run(strings.TrimSuffix(filepath.Base(file), ".json"), func(t *testing.T) {
			raw, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			var golden map[string]any
			if err := json.Unmarshal(raw, &golden); err != nil {
				t.Fatal(err)
			}
			got, err := BuildCase(testdata(t), golden["input"].(string))
			if err != nil {
				t.Fatal(err)
			}
			for _, key := range []string{"input", "sourceKind", "warnings", "converted", "modernized", "fixes", "findings"} {
				want := doc.ToPlain(golden[key])
				have := doc.ToPlain(got.Get(key))
				if !reflect.DeepEqual(want, have) {
					w, _ := json.MarshalIndent(want, "", " ")
					h, _ := json.MarshalIndent(have, "", " ")
					t.Errorf("%s mismatch\nwant: %s\nhave: %s", key, w, h)
				}
			}
		})
	}
}
