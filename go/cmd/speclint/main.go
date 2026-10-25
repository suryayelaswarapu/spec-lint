// Command speclint modernizes legacy API contracts into linted OpenAPI 3.1.
package main

import (
	"os"

	"github.com/suryayelaswarapu/spec-lint/go/internal/app"
)

func main() {
	os.Exit(app.Run(os.Args[1:], os.Stdout, os.Stderr))
}
