package convert

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// SOAP binding namespaces.
const (
	SOAP11NS = "http://schemas.xmlsoap.org/wsdl/soap/"
	SOAP12NS = "http://schemas.xmlsoap.org/wsdl/soap12/"
)

// Import limits; variables so tests can lower them.
var (
	MaxImportedDocs       = 50
	MaxImportBytes  int64 = specio.DefaultMaxBytes
)

func soapFaultSchema() *Map {
	return M(
		"type", "object",
		"description", "SOAP Fault translated to JSON.",
		"required", []any{"faultcode", "faultstring"},
		"properties", M(
			"faultcode", M("type", "string", "examples", []any{"soap:Server"}),
			"faultstring", M("type", "string"),
			"faultactor", M("type", "string"),
			"detail", M("description", "Application-specific fault detail.")))
}

type wsdlPart struct {
	name         string
	element, typ *QName
}

type wsdlFault struct {
	name string
	msg  QName
}

type wsdlOperation struct {
	name, doc     string
	input, output *QName
	faults        []wsdlFault
}

type bindingOp struct{ soapAction, style, use string }

type binding struct {
	name, soapVersion, style string
	portType                 QName
	ops                      map[string]bindingOp
}

type port struct {
	service, serviceDoc, name, address string
	binding                            QName
}

type selection struct {
	port     *port
	binding  *binding
	portType QName
}

// WSDLConverter converts WSDL 1.1 (SOAP 1.1/1.2) into an OpenAPI 3.1 JSON facade: each
// operation becomes POST /operation-name, messages become JSON bodies, every operation gets a
// 500 SoapFault, and SOAP details are kept under x-soap.
type WSDLConverter struct {
	warnings          []string
	baseDir           string
	allowLocalImports bool
	xsd               *XSDConverter
	messages          map[QName][]wsdlPart
	portTypeOrder     []QName
	portTypes         map[QName][]wsdlOperation
	bindingOrder      []QName
	bindings          map[QName]*binding
	ports             []port
	loaded            map[string]bool
}

// NewWSDLConverter creates a converter. baseDir bounds where local XSD imports may be read.
func NewWSDLConverter(baseDir string, allowLocalImports bool) *WSDLConverter {
	c := &WSDLConverter{baseDir: baseDir, allowLocalImports: allowLocalImports,
		messages: map[QName][]wsdlPart{}, portTypes: map[QName][]wsdlOperation{},
		bindings: map[QName]*binding{}, loaded: map[string]bool{}, warnings: []string{}}
	if baseDir != "" {
		if abs, err := filepath.Abs(baseDir); err == nil {
			c.baseDir = filepath.Clean(abs)
		}
	}
	c.xsd = NewXSDConverter(func(m string) { c.warnings = append(c.warnings, m) })
	return c
}

func (c *WSDLConverter) warn(format string, args ...any) {
	c.warnings = append(c.warnings, fmt.Sprintf(format, args...))
}

func (c *WSDLConverter) loadSchemas(container *Elem) error {
	var schemas []*Elem
	if container.Is(XSDNS, "schema") {
		schemas = append(schemas, container)
	}
	schemas = append(schemas, container.Descendants(XSDNS, "schema")...)
	for _, schema := range schemas {
		c.xsd.AddSchema(schema)
		imports := append(schema.Descendants(XSDNS, "import"), schema.Descendants(XSDNS, "include")...)
		for _, imp := range imports {
			if loc := imp.AttrOr("schemaLocation", ""); loc != "" {
				if err := c.loadExternal(loc); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func (c *WSDLConverter) loadExternal(location string) error {
	if strings.Contains(location, "://") || strings.HasPrefix(location, "//") {
		c.warn("Remote schema import '%s' was not fetched (remote fetching is disabled)", location)
		return nil
	}
	if !c.allowLocalImports || c.baseDir == "" {
		c.warn("Local schema import '%s' skipped (imports disabled)", location)
		return nil
	}
	target := filepath.Clean(filepath.Join(c.baseDir, location))
	if rel, err := filepath.Rel(c.baseDir, target); err != nil || rel == ".." ||
		strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return Errorf(ErrSecurity, "Schema import '%s' escapes the input directory", location)
	}
	if c.loaded[target] {
		return nil
	}
	if len(c.loaded) >= MaxImportedDocs {
		return Errorf(ErrSecurity, "More than %d imported schema documents", MaxImportedDocs)
	}
	info, err := os.Stat(target)
	if err != nil || !info.Mode().IsRegular() {
		c.warn("Schema import '%s' not found; skipped", location)
		return nil
	}
	if info.Size() > MaxImportBytes {
		return Errorf(ErrSecurity, "Imported schema '%s' exceeds the size limit", location)
	}
	c.loaded[target] = true
	data, err := os.ReadFile(target)
	if err != nil {
		return Errorf(ErrInput, "Cannot read schema import '%s'", location)
	}
	root, err := ParseXML(strings.TrimPrefix(string(data), "\uFEFF"))
	if err != nil {
		return err
	}
	return c.loadSchemas(root)
}

func (c *WSDLConverter) index(root *Elem) error {
	if !root.Is(WSDLNS, "definitions") {
		return Errorf(ErrInput, "Not a WSDL 1.1 document: root element must be wsdl:definitions")
	}
	tns := root.AttrOr("targetNamespace", "")
	for _, imp := range root.Kids(WSDLNS, "import") {
		c.warn("wsdl:import of '%s' is not supported; inline it instead", imp.AttrOr("location", "None"))
	}
	if types := root.Kid(WSDLNS, "types"); types != nil {
		if err := c.loadSchemas(types); err != nil {
			return err
		}
	}
	for _, msg := range root.Kids(WSDLNS, "message") {
		var parts []wsdlPart
		for _, p := range msg.Kids(WSDLNS, "part") {
			parts = append(parts, wsdlPart{p.AttrOr("name", "part"), p.ResolveAttr("element"),
				p.ResolveAttr("type")})
		}
		if parts == nil {
			parts = []wsdlPart{}
		}
		c.messages[QName{tns, msg.AttrOr("name", "")}] = parts
	}
	for _, pt := range root.Kids(WSDLNS, "portType") {
		var ops []wsdlOperation
		for _, op := range pt.Kids(WSDLNS, "operation") {
			o := wsdlOperation{name: op.AttrOr("name", "operation"), doc: Documentation(op)}
			if in := op.Kid(WSDLNS, "input"); in != nil {
				o.input = in.ResolveAttr("message")
			}
			if out := op.Kid(WSDLNS, "output"); out != nil {
				o.output = out.ResolveAttr("message")
			}
			for _, f := range op.Kids(WSDLNS, "fault") {
				if q := f.ResolveAttr("message"); q != nil {
					o.faults = append(o.faults, wsdlFault{f.AttrOr("name", "fault"), *q})
				}
			}
			ops = append(ops, o)
		}
		qn := QName{tns, pt.AttrOr("name", "")}
		if _, seen := c.portTypes[qn]; !seen {
			c.portTypeOrder = append(c.portTypeOrder, qn)
		}
		c.portTypes[qn] = ops
	}
	for _, b := range root.Kids(WSDLNS, "binding") {
		ptQN := b.ResolveAttr("type")
		if ptQN == nil {
			continue
		}
		bd := &binding{name: b.AttrOr("name", ""), portType: *ptQN, style: "document",
			ops: map[string]bindingOp{}}
		for _, pair := range [][2]string{{SOAP11NS, "1.1"}, {SOAP12NS, "1.2"}} {
			if sb := b.Kid(pair[0], "binding"); sb != nil {
				bd.soapVersion, bd.style = pair[1], sb.AttrOr("style", "document")
			}
		}
		for _, bop := range b.Kids(WSDLNS, "operation") {
			var op bindingOp
			useSet := false
			for _, ch := range bop.Descendants("", "") {
				if ch.NS != SOAP11NS && ch.NS != SOAP12NS {
					continue
				}
				switch {
				case ch.Local == "operation":
					op.soapAction = ch.AttrOr("soapAction", "")
					op.style = ch.AttrOr("style", "")
				case ch.Local == "body" && !useSet:
					op.use, useSet = ch.AttrOr("use", ""), true
				}
			}
			bd.ops[bop.AttrOr("name", "")] = op
		}
		qn := QName{tns, b.AttrOr("name", "")}
		if _, seen := c.bindings[qn]; !seen {
			c.bindingOrder = append(c.bindingOrder, qn)
		}
		c.bindings[qn] = bd
	}
	for _, svc := range root.Kids(WSDLNS, "service") {
		for _, p := range svc.Kids(WSDLNS, "port") {
			address := ""
			for _, ns := range []string{SOAP11NS, SOAP12NS} {
				if a := p.Kid(ns, "address"); a != nil {
					address = a.AttrOr("location", "")
				}
			}
			if bq := p.ResolveAttr("binding"); bq != nil {
				c.ports = append(c.ports, port{svc.AttrOr("name", "Service"), Documentation(svc),
					p.AttrOr("name", ""), address, *bq})
			}
		}
	}
	return nil
}

func rank(version string) int {
	if version == "1.1" {
		return 0
	}
	return 1
}

func (c *WSDLConverter) selected() []selection {
	var order []QName
	chosen := map[QName]selection{}
	put := func(pt QName, s selection) {
		if _, ok := chosen[pt]; !ok {
			order = append(order, pt)
		}
		chosen[pt] = s
	}
	for i := range c.ports {
		p := &c.ports[i]
		b := c.bindings[p.binding]
		if b == nil {
			c.warn("Port %s: binding not found", p.name)
			continue
		}
		if b.soapVersion == "" {
			c.warn("Port %s: non-SOAP binding skipped", p.name)
			continue
		}
		cur, ok := chosen[b.portType]
		if !ok || (cur.binding != nil && rank(b.soapVersion) < rank(cur.binding.soapVersion)) {
			put(b.portType, selection{p, b, b.portType})
		}
	}
	for _, qn := range c.bindingOrder {
		b := c.bindings[qn]
		if _, ok := chosen[b.portType]; b.soapVersion != "" && !ok {
			put(b.portType, selection{nil, b, b.portType})
		}
	}
	for _, pt := range c.portTypeOrder {
		if _, ok := chosen[pt]; !ok {
			put(pt, selection{nil, nil, pt})
		}
	}
	out := make([]selection, 0, len(order))
	for _, pt := range order {
		out = append(out, chosen[pt])
	}
	return out
}

func (c *WSDLConverter) messageSchema(msg *QName, style, wrapper string) *Map {
	if msg == nil {
		return nil
	}
	parts, ok := c.messages[*msg]
	if !ok {
		c.warn("Message %s not found", msg.Local)
		return nil
	}
	if len(parts) == 0 {
		return nil
	}
	if style == "document" && len(parts) == 1 && parts[0].element != nil {
		return c.xsd.ElementRef(parts[0].element)
	}
	props := NewMap()
	for _, p := range parts {
		if p.element != nil {
			props.Set(p.name, c.xsd.ElementRef(p.element))
		} else {
			props.Set(p.name, c.xsd.TypeRef(p.typ))
		}
	}
	schema := M("type", "object", "required", strList(props.Keys()), "properties", props)
	return Ref(c.xsd.NewComponent(wrapper, schema))
}

// Convert parses WSDL text and builds the OpenAPI document.
func (c *WSDLConverter) Convert(text string) (Result, error) {
	root, err := ParseXML(text)
	if err != nil {
		return Result{}, err
	}
	if err := c.index(root); err != nil {
		return Result{}, err
	}
	sel := c.selected()
	if len(sel) == 0 {
		return Result{}, Errorf(ErrInput, "WSDL defines no portType operations to convert")
	}
	paths := NewMap()
	tags := NewMap()
	opIDs := map[string]bool{}
	var addresses []any
	usesFault, warnedEncoded := false, false

	for _, s := range sel {
		tag := s.portType.Local
		if s.port != nil && len(sel) == 1 {
			tag = s.port.service
		}
		tagObj := M("name", tag)
		if s.port != nil && s.port.serviceDoc != "" {
			tagObj.Set("description", s.port.serviceDoc)
		}
		tags.SetDefault(tag, tagObj)
		if s.port != nil && s.port.address != "" && !Contains(addresses, s.port.address) {
			addresses = append(addresses, s.port.address)
		}
		for _, op := range c.portTypes[s.portType] {
			var bop *bindingOp
			if s.binding != nil {
				if b, ok := s.binding.ops[op.name]; ok {
					bop = &b
				}
			}
			style := "document"
			if bop != nil && bop.style != "" {
				style = bop.style
			} else if s.binding != nil {
				style = s.binding.style
			}
			if bop != nil && bop.use == "encoded" && !warnedEncoded {
				warnedEncoded = true
				c.warn("rpc/encoded binding detected (not WS-I compliant); mapped as literal")
			}
			path := "/" + ToKebab(op.name)
			if paths.Has(path) {
				path = "/" + ToKebab(s.portType.Local) + "/" + ToKebab(op.name)
			}
			for n, base := 2, path; paths.Has(path); n++ {
				path = fmt.Sprintf("%s-%d", base, n)
			}
			opID := Unique(ToCamel(op.name), InSet(opIDs))
			opIDs[opID] = true

			operation := M("tags", []any{tag}, "summary", op.name, "operationId", opID)
			if op.doc != "" {
				operation.Set("description", op.doc)
			}
			if req := c.messageSchema(op.input, style, op.name+"Request"); req != nil {
				operation.Set("requestBody", M("required", true,
					"content", M("application/json", M("schema", req))))
			}
			responses := NewMap()
			resp := c.messageSchema(op.output, style, op.name+"Response")
			switch {
			case op.output == nil:
				responses.Set("202", M("description", "Accepted (one-way operation)"))
			case resp != nil:
				responses.Set("200", M("description", "Successful response",
					"content", M("application/json", M("schema", resp))))
			default:
				responses.Set("204", M("description", "Successful response (empty)"))
			}
			usesFault = true
			fault := M("$ref", "#/components/schemas/SoapFault")
			var details []any
			for _, f := range op.faults {
				msg := f.msg
				if d := c.messageSchema(&msg, "document", f.name+"Detail"); d != nil {
					details = append(details, d)
				}
			}
			if len(details) > 0 {
				var detail any = details[0]
				if len(details) > 1 {
					detail = M("oneOf", details)
				}
				fault = M("allOf", []any{fault, M("properties", M("detail", detail))})
			}
			responses.Set("500", M("description", "SOAP Fault",
				"content", M("application/json", M("schema", fault))))
			operation.Set("responses", responses)

			soap := M("operation", op.name, "style", style)
			if bop != nil && bop.soapAction != "" {
				soap.Set("soapAction", bop.soapAction)
			}
			if s.binding != nil {
				soap.Set("binding", s.binding.name)
				soap.Set("soapVersion", s.binding.soapVersion)
			}
			if s.port != nil {
				soap.Set("service", s.port.service)
				soap.Set("port", s.port.name)
			}
			operation.Set("x-soap", soap)
			paths.Set(path, M("post", operation))
		}
	}

	schemas := NewMap()
	c.xsd.Components().Each(schemas.Set)
	if usesFault {
		schemas.Set("SoapFault", soapFaultSchema())
	}
	title := ""
	for _, s := range sel {
		if s.port != nil {
			title = s.port.service
			break
		}
	}
	if title == "" {
		title = root.AttrOr("name", "")
	}
	if title == "" {
		title = "SOAP"
	}
	note := "Generated by spec-lint from a WSDL 1.1 contract (JSON facade over SOAP)."
	info := M("title", title, "version", "1.0.0",
		"description", strings.TrimSpace(Documentation(root)+"\n\n"+note))
	c.warn("WSDL has no API version; info.version set to 1.0.0")

	server := M("url", "/", "description",
		"REST facade (deploy behind an API gateway that calls the SOAP backend)")
	if len(addresses) > 0 {
		server.Set("x-soap-addresses", addresses)
	}
	var tagList []any
	for _, k := range tags.Keys() {
		tagList = append(tagList, tags.Get(k))
	}
	out := M("openapi", "3.1.0", "info", info, "servers", []any{server}, "tags", tagList,
		"paths", paths, "components", M("schemas", schemas))
	return Result{Document: out, SourceKind: specio.WSDL11, Warnings: c.warnings}, nil
}
