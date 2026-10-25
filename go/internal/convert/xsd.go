package convert

import (
	"math/big"
	"regexp"
	"strconv"
	"strings"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
)

// XML namespaces used by the WSDL/XSD converters.
const (
	XSDNS     = "http://www.w3.org/2001/XMLSchema"
	SOAPEncNS = "http://schemas.xmlsoap.org/soap/encoding/"
	WSDLNS    = "http://schemas.xmlsoap.org/wsdl/"
)

var (
	componentNameRe = regexp.MustCompile(`[^A-Za-z0-9._-]`)
	decimalRe       = regexp.MustCompile(`^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?\s*$`)
	integerRe       = regexp.MustCompile(`^\s*[+-]?\d+\s*$`)
	numericFacets   = map[string]string{"minInclusive": "minimum", "maxInclusive": "maximum",
		"minExclusive": "exclusiveMinimum", "maxExclusive": "exclusiveMaximum"}
)

func builtins() map[string]*Map {
	b := map[string]*Map{}
	for _, n := range []string{"string", "normalizedString", "token", "language", "Name", "NCName",
		"ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES", "NMTOKEN", "NMTOKENS", "QName", "NOTATION",
		"gYear", "gYearMonth", "gMonth", "gMonthDay", "gDay"} {
		b[n] = M("type", "string")
	}
	b["anyURI"] = M("type", "string", "format", "uri")
	b["boolean"] = M("type", "boolean")
	b["decimal"] = M("type", "number")
	b["float"] = M("type", "number", "format", "float")
	b["double"] = M("type", "number", "format", "double")
	b["integer"] = M("type", "integer")
	b["int"] = M("type", "integer", "format", "int32")
	b["long"] = M("type", "integer", "format", "int64")
	b["short"] = M("type", "integer", "minimum", int64(-32768), "maximum", int64(32767))
	b["byte"] = M("type", "integer", "minimum", int64(-128), "maximum", int64(127))
	b["nonNegativeInteger"] = M("type", "integer", "minimum", int64(0))
	b["positiveInteger"] = M("type", "integer", "minimum", int64(1))
	b["nonPositiveInteger"] = M("type", "integer", "maximum", int64(0))
	b["negativeInteger"] = M("type", "integer", "maximum", int64(-1))
	b["unsignedLong"] = M("type", "integer", "format", "int64", "minimum", int64(0))
	b["unsignedInt"] = M("type", "integer", "format", "int64", "minimum", int64(0),
		"maximum", int64(4294967295))
	b["unsignedShort"] = M("type", "integer", "minimum", int64(0), "maximum", int64(65535))
	b["unsignedByte"] = M("type", "integer", "minimum", int64(0), "maximum", int64(255))
	b["date"] = M("type", "string", "format", "date")
	b["dateTime"] = M("type", "string", "format", "date-time")
	b["time"] = M("type", "string", "format", "time")
	b["duration"] = M("type", "string", "format", "duration")
	b["base64Binary"] = M("type", "string", "contentEncoding", "base64")
	b["hexBinary"] = M("type", "string", "pattern", "^([0-9a-fA-F]{2})*$")
	b["anyType"] = NewMap()
	b["anySimpleType"] = NewMap()
	return b
}

var xsdBuiltins = builtins()

// XSDConverter maps XML Schema to JSON Schema 2020-12. Named types become components emitted
// lazily, so only reachable types appear in the output.
type XSDConverter struct {
	warn                                func(string)
	warned                              map[string]bool
	elements, complexTypes, simpleTypes map[QName]*Elem
	groups, attributeGroups, attributes map[QName]*Elem
	components                          *Map
	names                               map[string]string
}

// NewXSDConverter creates a converter reporting warnings through warn.
func NewXSDConverter(warn func(string)) *XSDConverter {
	return &XSDConverter{
		warn: warn, warned: map[string]bool{},
		elements: map[QName]*Elem{}, complexTypes: map[QName]*Elem{}, simpleTypes: map[QName]*Elem{},
		groups: map[QName]*Elem{}, attributeGroups: map[QName]*Elem{}, attributes: map[QName]*Elem{},
		components: NewMap(), names: map[string]string{},
	}
}

// Components returns the generated component schemas.
func (x *XSDConverter) Components() *Map { return x.components }

// AddSchema indexes the global declarations of an xsd:schema element.
func (x *XSDConverter) AddSchema(schema *Elem) {
	tns := schema.AttrOr("targetNamespace", "")
	tables := map[string]map[QName]*Elem{"element": x.elements, "complexType": x.complexTypes,
		"simpleType": x.simpleTypes, "group": x.groups, "attributeGroup": x.attributeGroups,
		"attribute": x.attributes}
	for _, c := range schema.Children {
		name := c.AttrOr("name", "")
		table, ok := tables[c.Local]
		if c.NS != XSDNS || !ok || name == "" {
			continue
		}
		if _, exists := table[QName{tns, name}]; !exists {
			table[QName{tns, name}] = c
		}
	}
}

func (x *XSDConverter) warnOnce(msg string) {
	if !x.warned[msg] {
		x.warned[msg] = true
		x.warn(msg)
	}
}

func (x *XSDConverter) componentName(kind string, qn QName) (string, bool) {
	key := kind + "|" + qn.NS + "|" + qn.Local
	if name, ok := x.names[key]; ok {
		return name, false
	}
	base := ToPascal(componentNameRe.ReplaceAllString(qn.Local, "_"))
	if base == "" {
		base = "Schema"
	}
	name := Unique(base, InMap(x.components))
	x.names[key] = name
	x.components.Set(name, NewMap()) // placeholder: supports recursive types
	return name, true
}

// NewComponent registers a synthetic component (e.g. an RPC request wrapper).
func (x *XSDConverter) NewComponent(preferred string, schema *Map) string {
	name := Unique(ToPascal(preferred), InMap(x.components))
	x.components.Set(name, schema)
	return name
}

// Ref builds a component reference.
func Ref(name string) *Map { return M("$ref", "#/components/schemas/"+name) }

// TypeRef returns the schema for a type QName: builtins inline, named types as $ref.
func (x *XSDConverter) TypeRef(qn *QName) *Map {
	if qn == nil {
		return NewMap()
	}
	if b, ok := xsdBuiltins[qn.Local]; ok && (qn.NS == XSDNS || qn.NS == SOAPEncNS) {
		return CopyMap(b)
	}
	if qn.NS == SOAPEncNS && qn.Local == "Array" {
		return M("type", "array")
	}
	if el, ok := x.complexTypes[*qn]; ok {
		name, isNew := x.componentName("type", *qn)
		if isNew {
			x.components.Set(name, x.describe(x.complex(el, 0), el))
		}
		return Ref(name)
	}
	if el, ok := x.simpleTypes[*qn]; ok {
		name, isNew := x.componentName("type", *qn)
		if isNew {
			x.components.Set(name, x.describe(x.simple(el, 0), el))
		}
		return Ref(name)
	}
	x.warnOnce("Unknown XSD type {" + qn.NS + "}" + qn.Local + "; mapped to an unconstrained schema")
	return NewMap()
}

// ElementRef returns the schema for a global element QName (document/literal message parts).
func (x *XSDConverter) ElementRef(qn *QName) *Map {
	if qn == nil {
		return NewMap()
	}
	el, ok := x.elements[*qn]
	if !ok {
		x.warnOnce("Unknown XSD element {" + qn.NS + "}" + qn.Local + "; mapped to {}")
		return NewMap()
	}
	inline := el.Kid(XSDNS, "complexType") != nil || el.Kid(XSDNS, "simpleType") != nil
	nillable, _ := el.Attr("nillable")
	if _, hasType := el.Attr("type"); !inline && hasType && nillable != "true" {
		return x.TypeRef(el.ResolveAttr("type"))
	}
	name, isNew := x.componentName("element", *qn)
	if isNew {
		x.components.Set(name, x.elementBody(el, 0))
	}
	return Ref(name)
}

var spaceRe = regexp.MustCompile(`\s+`)

// Documentation returns xsd:annotation/xsd:documentation or wsdl:documentation text.
func Documentation(e *Elem) string {
	if ann := e.Kid(XSDNS, "annotation"); ann != nil {
		if d := ann.Kid(XSDNS, "documentation"); d != nil {
			if text := strings.TrimSpace(spaceRe.ReplaceAllString(d.TextContent(), " ")); text != "" {
				return text
			}
		}
	}
	if d := e.Kid(WSDLNS, "documentation"); d != nil {
		return strings.TrimSpace(spaceRe.ReplaceAllString(d.TextContent(), " "))
	}
	return ""
}

func (x *XSDConverter) describe(schema *Map, e *Elem) *Map {
	if d := Documentation(e); d != "" && !schema.Has("$ref") {
		schema.SetDefault("description", d)
	}
	return schema
}

func occurs(e *Elem) (int, int) {
	lo, err := strconv.Atoi(strings.TrimSpace(e.AttrOr("minOccurs", "1")))
	if err != nil {
		lo = 1
	}
	raw := e.AttrOr("maxOccurs", "1")
	if raw == "unbounded" {
		return lo, -1
	}
	hi, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil {
		hi = 1
	}
	return lo, hi
}

func addNullXSD(s *Map) *Map {
	if s.Has("type") && !s.Has("$ref") {
		var types []any
		if l, ok := s.Get("type").([]any); ok {
			types = append(types, l...)
		} else {
			types = []any{s.Get("type")}
		}
		s.Set("type", append(types, "null"))
		return s
	}
	return M("anyOf", []any{s, M("type", "null")})
}

func (x *XSDConverter) elementBody(el *Elem, depth int) *Map {
	var schema *Map
	primitive := ""
	switch {
	case el.Kid(XSDNS, "complexType") != nil:
		schema = x.complex(el.Kid(XSDNS, "complexType"), depth+1)
	case el.Kid(XSDNS, "simpleType") != nil:
		schema = x.simple(el.Kid(XSDNS, "simpleType"), depth+1)
	case el.ResolveAttr("type") != nil:
		qn := el.ResolveAttr("type")
		schema = x.TypeRef(qn)
		primitive = x.primitive(qn, 0)
	default:
		schema = NewMap()
	}
	x.applyValueConstraints(schema, el, primitive)
	if v, _ := el.Attr("nillable"); v == "true" {
		schema = addNullXSD(schema)
	}
	return x.describe(schema, el)
}

func (x *XSDConverter) applyValueConstraints(schema *Map, node *Elem, primitive string) {
	if v, ok := node.Attr("default"); ok {
		schema.Set("default", typed(schema, v, primitive))
	}
	if v, ok := node.Attr("fixed"); ok {
		schema.Set("const", typed(schema, v, primitive))
	}
}

func (x *XSDConverter) elementParticle(el *Elem, depth int) (string, *Map, bool) {
	lo, hi := occurs(el)
	var name string
	var schema *Map
	if ref, ok := el.Attr("ref"); ok {
		qn := el.Resolve(ref)
		name = "value"
		if qn != nil {
			name = qn.Local
		}
		schema = x.ElementRef(qn)
	} else {
		name = el.AttrOr("name", "value")
		schema = x.elementBody(el, depth)
	}
	if hi == -1 || hi > 1 {
		arr := M("type", "array", "items", schema)
		if lo > 1 {
			arr.Set("minItems", int64(lo))
		}
		if hi != -1 {
			arr.Set("maxItems", int64(hi))
		}
		schema = arr
	}
	return name, schema, lo > 0
}

type objectBuilder struct {
	properties *Map
	required   []string
	choices    [][][]string
}

func newObjectBuilder() *objectBuilder { return &objectBuilder{properties: NewMap()} }

func (o *objectBuilder) add(name string, schema *Map, required bool) string {
	key := name
	if o.properties.Has(name) {
		key = Unique("@"+name, InMap(o.properties))
	}
	o.properties.Set(key, schema)
	if required {
		o.required = append(o.required, key)
	}
	return key
}

func strList(s []string) []any {
	out := make([]any, len(s))
	for i, v := range s {
		out[i] = v
	}
	return out
}

func (o *objectBuilder) build() *Map {
	out := M("type", "object")
	if o.properties.Len() > 0 {
		out.Set("properties", o.properties)
	}
	if len(o.required) > 0 {
		out.Set("required", strList(o.required))
	}
	var groups []any
	for _, choice := range o.choices {
		ok := true
		for _, b := range choice {
			ok = ok && len(b) > 0
		}
		if !ok {
			continue
		}
		var oneOf []any
		for _, b := range choice {
			oneOf = append(oneOf, M("required", strList(b)))
		}
		groups = append(groups, M("oneOf", oneOf))
	}
	if len(groups) == 1 {
		out.Set("oneOf", AsMap(groups[0]).Get("oneOf"))
	} else if len(groups) > 1 {
		out.Set("allOf", groups)
	}
	return out
}

func (x *XSDConverter) walk(kids []*Elem, obj *objectBuilder, depth int) {
	if depth > maxDepth {
		x.warnOnce("XSD nesting deeper than 64 levels was truncated")
		return
	}
	for _, c := range kids {
		if c.NS != XSDNS {
			continue
		}
		switch c.Local {
		case "element":
			name, schema, req := x.elementParticle(c, depth)
			obj.add(name, schema, req)
		case "sequence", "all":
			if _, hi := occurs(c); hi != 1 {
				x.warnOnce("Repeating <sequence> groups are flattened into one object")
			}
			x.walk(c.Children, obj, depth+1)
		case "choice":
			x.choice(c, obj, depth)
		case "group":
			if g := x.groups[resolveOrEmpty(c)]; g != nil {
				x.walk(g.Children, obj, depth+1)
			}
		case "attribute":
			x.attribute(c, obj)
		case "attributeGroup":
			if g := x.attributeGroups[resolveOrEmpty(c)]; g != nil {
				x.walk(g.Children, obj, depth+1)
			}
		}
	}
}

func resolveOrEmpty(e *Elem) QName {
	if qn := e.ResolveAttr("ref"); qn != nil {
		return *qn
	}
	return QName{}
}

func (x *XSDConverter) choice(choice *Elem, obj *objectBuilder, depth int) {
	var branches [][]string
	for _, branch := range choice.Children {
		if branch.NS != XSDNS || branch.Local == "annotation" {
			continue
		}
		sub := newObjectBuilder()
		x.walk([]*Elem{branch}, sub, depth+1)
		var req []string
		for _, k := range sub.properties.Keys() {
			key := obj.add(k, AsMap(sub.properties.Get(k)), false)
			for _, r := range sub.required {
				if r == k {
					req = append(req, key)
				}
			}
		}
		branches = append(branches, req)
	}
	if len(branches) > 0 && choice.AttrOr("minOccurs", "1") != "0" {
		obj.choices = append(obj.choices, branches)
	}
}

func (x *XSDConverter) attribute(a *Elem, obj *objectBuilder) {
	if ref, ok := a.Attr("ref"); ok {
		qn := a.Resolve(ref)
		if qn == nil || x.attributes[*qn] == nil {
			return
		}
		a = x.attributes[*qn]
	}
	name := a.AttrOr("name", "")
	if name == "" || a.AttrOr("use", "") == "prohibited" {
		return
	}
	var schema *Map
	primitive := ""
	switch {
	case a.Kid(XSDNS, "simpleType") != nil:
		schema = x.simple(a.Kid(XSDNS, "simpleType"), 1)
	case a.ResolveAttr("type") != nil:
		qn := a.ResolveAttr("type")
		schema, primitive = x.TypeRef(qn), x.primitive(qn, 0)
	default:
		schema = M("type", "string")
	}
	x.applyValueConstraints(schema, a, primitive)
	x.describe(schema, a)
	obj.add(name, schema, a.AttrOr("use", "") == "required")
}

func (x *XSDConverter) complex(ct *Elem, depth int) *Map {
	if ct.AttrOr("mixed", "") == "true" {
		x.warnOnce("Mixed-content complex types: text content is not represented")
	}
	if cc := ct.Kid(XSDNS, "complexContent"); cc != nil {
		return x.complexContent(cc, depth)
	}
	if sc := ct.Kid(XSDNS, "simpleContent"); sc != nil {
		return x.simpleContent(sc, depth)
	}
	obj := newObjectBuilder()
	x.walk(ct.Children, obj, depth+1)
	return obj.build()
}

func (x *XSDConverter) complexContent(cc *Elem, depth int) *Map {
	ext, res := cc.Kid(XSDNS, "extension"), cc.Kid(XSDNS, "restriction")
	node := ext
	if node == nil {
		node = res
	}
	if node == nil {
		return M("type", "object")
	}
	base := node.ResolveAttr("base")
	if res != nil && base != nil && *base == (QName{SOAPEncNS, "Array"}) {
		return x.soapencArray(res, depth)
	}
	obj := newObjectBuilder()
	x.walk(node.Children, obj, depth+1)
	own := obj.build()
	if ext != nil && base != nil {
		baseSchema := x.TypeRef(base)
		if Truthy(own.Get("properties")) {
			return M("allOf", []any{baseSchema, own})
		}
		return baseSchema
	}
	return own
}

func (x *XSDConverter) soapencArray(res *Elem, depth int) *Map {
	for _, a := range res.Descendants(XSDNS, "attribute") {
		if raw, ok := a.AttrNS(WSDLNS, "arrayType"); ok && raw != "" {
			item := a.Resolve(strings.SplitN(raw, "[", 2)[0])
			return M("type", "array", "items", x.TypeRef(item))
		}
	}
	obj := newObjectBuilder()
	x.walk(res.Children, obj, depth+1)
	if obj.properties.Len() == 1 {
		only := AsMap(obj.properties.Get(obj.properties.Keys()[0]))
		if only.Get("type") == "array" {
			return CopyMap(only)
		}
	}
	return M("type", "array")
}

func (x *XSDConverter) simpleContent(sc *Elem, depth int) *Map {
	node := sc.Kid(XSDNS, "extension")
	if node == nil {
		node = sc.Kid(XSDNS, "restriction")
	}
	if node == nil {
		return M("type", "object")
	}
	obj := newObjectBuilder()
	obj.add("value", x.TypeRef(node.ResolveAttr("base")), true)
	x.walk(node.Children, obj, depth+1)
	x.warnOnce("simpleContent text is mapped to a 'value' property")
	return obj.build()
}

func (x *XSDConverter) simple(st *Elem, depth int) *Map {
	if depth > maxDepth {
		return NewMap()
	}
	if res := st.Kid(XSDNS, "restriction"); res != nil {
		return x.restriction(res, depth)
	}
	if lst := st.Kid(XSDNS, "list"); lst != nil {
		var items *Map
		if inner := lst.Kid(XSDNS, "simpleType"); inner != nil {
			items = x.simple(inner, depth+1)
		} else {
			items = x.TypeRef(lst.ResolveAttr("itemType"))
		}
		x.warnOnce("xsd:list (space-separated values) is mapped to a JSON array")
		return M("type", "array", "items", items)
	}
	if uni := st.Kid(XSDNS, "union"); uni != nil {
		var members []any
		for _, m := range strings.Fields(uni.AttrOr("memberTypes", "")) {
			members = append(members, x.TypeRef(uni.Resolve(m)))
		}
		for _, s := range uni.Kids(XSDNS, "simpleType") {
			members = append(members, x.simple(s, depth+1))
		}
		if len(members) == 0 {
			return NewMap()
		}
		return M("anyOf", members)
	}
	return NewMap()
}

func (x *XSDConverter) primitive(qn *QName, depth int) string {
	if qn == nil || depth > maxDepth {
		return ""
	}
	if qn.NS == XSDNS || qn.NS == SOAPEncNS {
		if b, ok := xsdBuiltins[qn.Local]; ok {
			t, _ := b.Get("type").(string)
			return t
		}
		return ""
	}
	g := x.simpleTypes[*qn]
	if g == nil {
		return ""
	}
	res := g.Kid(XSDNS, "restriction")
	if res == nil {
		return ""
	}
	return x.primitive(res.ResolveAttr("base"), depth+1)
}

func parseInteger(raw string) any {
	raw = strings.TrimPrefix(strings.TrimSpace(raw), "+")
	if i, err := strconv.ParseInt(raw, 10, 64); err == nil {
		return i
	}
	b, _ := new(big.Int).SetString(raw, 10)
	return b
}

// typed converts a lexical XSD value to the JSON value of the schema's primitive type.
func typed(schema *Map, raw, primitive string) any {
	kind := primitive
	if kind == "" {
		switch t := schema.Get("type").(type) {
		case string:
			kind = t
		case []any:
			for _, k := range t {
				if k != "null" {
					kind = Str(k)
					break
				}
			}
		}
	}
	switch kind {
	case "integer":
		if integerRe.MatchString(raw) {
			return parseInteger(raw)
		}
	case "number":
		if decimalRe.MatchString(raw) {
			if strings.ContainsAny(raw, ".eE") {
				f, _ := strconv.ParseFloat(strings.TrimSpace(raw), 64)
				return f
			}
			return parseInteger(raw)
		}
	case "boolean":
		v := strings.TrimSpace(raw)
		return v == "true" || v == "1"
	}
	return raw
}

func (x *XSDConverter) restriction(res *Elem, depth int) *Map {
	baseQN := res.ResolveAttr("base")
	var base *Map
	if inner := res.Kid(XSDNS, "simpleType"); inner != nil {
		base = x.simple(inner, depth+1)
	} else {
		base = x.TypeRef(baseQN)
	}
	prim := x.primitive(baseQN, 0)
	if t, ok := base.Get("type").(string); ok && prim == "" {
		prim = t
	}
	schema := NewMap()
	base.Each(schema.Set)
	var enum []any
	var patterns []string
	for _, f := range res.Children {
		value, ok := f.Attr("value")
		if f.NS != XSDNS || !ok {
			continue
		}
		switch f.Local {
		case "enumeration":
			enum = append(enum, typed(schema, value, prim))
		case "pattern":
			patterns = append(patterns, value)
		case "minLength", "maxLength":
			n, _ := strconv.Atoi(strings.TrimSpace(value))
			schema.Set(f.Local, int64(n))
		case "length":
			n, _ := strconv.Atoi(strings.TrimSpace(value))
			schema.Set("minLength", int64(n))
			schema.Set("maxLength", int64(n))
		default:
			if key, ok := numericFacets[f.Local]; ok {
				p := prim
				if p == "" {
					p = "number"
				}
				schema.Set(key, typed(schema, value, p))
			}
		}
	}
	if len(enum) > 0 {
		schema.Set("enum", enum)
	}
	if len(patterns) > 0 {
		// XSD patterns are implicitly anchored; JSON Schema patterns are not.
		schema.Set("pattern", "^(?:"+strings.Join(patterns, "|")+")$")
	}
	return schema
}
