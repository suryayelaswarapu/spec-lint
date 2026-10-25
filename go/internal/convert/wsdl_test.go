package convert

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
)

const head = `<?xml version="1.0"?>
<definitions xmlns="http://schemas.xmlsoap.org/wsdl/"
  xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
  xmlns:soap12="http://schemas.xmlsoap.org/wsdl/soap12/"
  xmlns:http="http://schemas.xmlsoap.org/wsdl/http/"
  xmlns:xs="http://www.w3.org/2001/XMLSchema"
  xmlns:tns="urn:t" targetNamespace="urn:t" name="T">
`

func wsdl(body, types string) string {
	return head + `<types><xs:schema targetNamespace="urn:t">` + types + `</xs:schema></types>` + body + `</definitions>`
}

func oneOp(types, input string) string {
	return wsdl(`<message name="In"><part name="p" element="`+input+`"/></message>
<portType name="P"><operation name="Do"><input message="tns:In"/></operation></portType>`, types)
}

func conv(t *testing.T, text string) Result {
	t.Helper()
	r, err := NewWSDLConverter("", true).Convert(text)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func schemasOf(t *testing.T, text string) *Map {
	return AsMap(at(conv(t, text).Document, "components", "schemas"))
}

func TestXSDFacetsAndLists(t *testing.T) {
	types := `
<xs:simpleType name="Code"><xs:restriction base="xs:string">
  <xs:length value="3"/><xs:pattern value="[A-Z]+"/><xs:pattern value="[0-9]+"/>
</xs:restriction></xs:simpleType>
<xs:simpleType name="Temp"><xs:restriction base="xs:decimal">
  <xs:minExclusive value="-1.5"/><xs:maxExclusive value="60"/><xs:minLength value="1"/><xs:maxLength value="9"/>
</xs:restriction></xs:simpleType>
<xs:simpleType name="Level"><xs:restriction base="xs:int"><xs:enumeration value="1"/><xs:enumeration value="2"/></xs:restriction></xs:simpleType>
<xs:simpleType name="Child"><xs:restriction base="tns:Level"><xs:enumeration value="1"/></xs:restriction></xs:simpleType>
<xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>
<xs:simpleType name="Inline"><xs:list><xs:simpleType><xs:restriction base="xs:int"/></xs:simpleType></xs:list></xs:simpleType>
<xs:simpleType name="Either"><xs:union memberTypes="xs:int xs:boolean"><xs:simpleType><xs:restriction base="xs:string"/></xs:simpleType></xs:union></xs:simpleType>
<xs:simpleType name="Nothing"><xs:union/></xs:simpleType>
<xs:simpleType name="Empty"/>
<xs:simpleType name="Bad"><xs:restriction base="xs:int"><xs:enumeration value="x"/></xs:restriction></xs:simpleType>
<xs:simpleType name="Flag"><xs:restriction base="xs:boolean"/></xs:simpleType>
<xs:simpleType name="Wrapped"><xs:restriction><xs:simpleType><xs:restriction base="xs:int"/></xs:simpleType><xs:maxInclusive value="5"/></xs:restriction></xs:simpleType>
<xs:simpleType name="Chain"><xs:restriction base="tns:Wrapped"/></xs:simpleType>
<xs:simpleType name="ListBase"><xs:restriction base="tns:Codes"/></xs:simpleType>
<xs:element name="Req"><xs:complexType><xs:sequence>
  <xs:element name="code" type="tns:Code"/><xs:element name="temp" type="tns:Temp"/>
  <xs:element name="level" type="tns:Level"/><xs:element name="child" type="tns:Child"/>
  <xs:element name="codes" type="tns:Codes"/><xs:element name="inline" type="tns:Inline"/>
  <xs:element name="either" type="tns:Either"/><xs:element name="nothing" type="tns:Nothing"/>
  <xs:element name="empty" type="tns:Empty"/><xs:element name="bad" type="tns:Bad"/>
  <xs:element name="wrapped" type="tns:Wrapped"/><xs:element name="chain" type="tns:Chain" default="3"/>
  <xs:element name="lb" type="tns:ListBase"/>
  <xs:element name="flag" type="tns:Flag" default="true"/>
  <xs:element name="fixed" type="xs:int" fixed="7"/>
  <xs:element name="ratio" type="xs:float" default="0.5"/>
  <xs:element name="whole" type="xs:double" default="2"/>
  <xs:element name="big" type="xs:integer" default="99999999999999999999"/>
  <xs:element name="word" type="xs:int" default="many"/>
  <xs:element name="nil" type="xs:string" nillable="true" default="n"/>
  <xs:element name="any"/>
  <xs:element name="raw" type="xs:base64Binary"/>
  <xs:element name="encoded" type="soapenc:string" xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/"/>
</xs:sequence></xs:complexType></xs:element>`
	s := schemasOf(t, oneOp(types, "tns:Req"))
	checks := map[string]bool{
		"code":   eq(s.Get("Code"), M("type", "string", "minLength", 3, "maxLength", 3, "pattern", "^(?:[A-Z]+|[0-9]+)$")),
		"temp":   eq(s.Get("Temp"), M("type", "number", "exclusiveMinimum", -1.5, "exclusiveMaximum", 60, "minLength", 1, "maxLength", 9)),
		"level":  eq(at(s, "Level", "enum"), []any{1, 2}),
		"child":  at(s, "Child", "$ref") == "#/components/schemas/Level",
		"inline": at(s, "Inline", "items", "format") == "int32",
		"either": len(AsList(at(s, "Either", "anyOf"))) == 3,
		"empty":  AsMap(s.Get("Nothing")).Len() == 0 && AsMap(s.Get("Empty")).Len() == 0,
		"bad":    eq(at(s, "Bad", "enum"), []any{"x"}),
		"wrap":   eq(at(s, "Wrapped", "maximum"), 5),
		"chain":  at(s, "Req", "properties", "chain", "default") == "3", // no named base: stays lexical
		"flag":   at(s, "Req", "properties", "flag", "default") == true,
		"fixed":  eq(at(s, "Req", "properties", "fixed", "const"), 7),
		"ratio":  eq(at(s, "Req", "properties", "ratio", "default"), 0.5),
		"whole":  eq(at(s, "Req", "properties", "whole", "default"), 2),
		"big":    Str(at(s, "Req", "properties", "big", "default")) == "99999999999999999999",
		"word":   at(s, "Req", "properties", "word", "default") == "many",
		"nil":    eq(at(s, "Req", "properties", "nil", "type"), []any{"string", "null"}),
		"any":    AsMap(at(s, "Req", "properties", "any")).Len() == 0,
		"enc":    at(s, "Req", "properties", "encoded", "type") == "string",
	}
	for name, ok := range checks {
		if !ok {
			t.Errorf("check %s failed", name)
		}
	}
}

func TestXSDComplexFeatures(t *testing.T) {
	types := `
<xs:group name="Audit"><xs:sequence><xs:element name="by" type="xs:string"/></xs:sequence></xs:group>
<xs:attributeGroup name="Meta"><xs:attribute name="lang" type="xs:language"/></xs:attributeGroup>
<xs:attribute name="globalAttr" type="xs:string"/>
<xs:complexType name="Base"><xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence></xs:complexType>
<xs:complexType name="Same"><xs:complexContent><xs:extension base="tns:Base"/></xs:complexContent></xs:complexType>
<xs:complexType name="More"><xs:annotation><xs:documentation>  more
  stuff </xs:documentation></xs:annotation><xs:complexContent><xs:extension base="tns:Base"><xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
<xs:complexType name="Restricted"><xs:complexContent><xs:restriction base="tns:Base"><xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence></xs:restriction></xs:complexContent></xs:complexType>
<xs:complexType name="Empty"><xs:complexContent/></xs:complexType>
<xs:complexType name="Measure"><xs:simpleContent><xs:extension base="xs:double"><xs:attribute name="unit" type="xs:string" use="required"/></xs:extension></xs:simpleContent></xs:complexType>
<xs:complexType name="Restr"><xs:simpleContent><xs:restriction base="xs:double"/></xs:simpleContent></xs:complexType>
<xs:complexType name="EmptySimple"><xs:simpleContent/></xs:complexType>
<xs:complexType name="Mixed" mixed="true"><xs:sequence><xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
<xs:complexType name="Node"><xs:sequence><xs:element name="child" type="tns:Node" minOccurs="0" maxOccurs="unbounded"/></xs:sequence></xs:complexType>
<xs:complexType name="Pair"><xs:sequence><xs:element name="v" type="xs:int" minOccurs="2" maxOccurs="2"/></xs:sequence></xs:complexType>
<xs:element name="Shared" type="xs:string"/>
<xs:element name="Req"><xs:complexType>
  <xs:sequence maxOccurs="unbounded">
    <xs:annotation><xs:documentation>ignored</xs:documentation></xs:annotation>
    <xs:group ref="tns:Audit"/><xs:group ref="tns:Missing"/>
    <xs:element ref="tns:Shared"/>
    <xs:element name="same" type="tns:Same"/><xs:element name="more" type="tns:More"/>
    <xs:element name="restricted" type="tns:Restricted"/><xs:element name="empty" type="tns:Empty"/>
    <xs:element name="measure" type="tns:Measure"/><xs:element name="restr" type="tns:Restr"/>
    <xs:element name="emptySimple" type="tns:EmptySimple"/><xs:element name="mixed" type="tns:Mixed"/>
    <xs:element name="node" type="tns:Node"/><xs:element name="pair" type="tns:Pair"/>
    <xs:element name="unknown" type="tns:DoesNotExist"/>
    <xs:element name="id" type="xs:string"/>
    <xs:choice minOccurs="0"><xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:string"/></xs:choice>
    <xs:choice><xs:annotation/><xs:sequence><xs:element name="c" type="xs:string"/></xs:sequence><xs:element name="d" type="xs:string"/></xs:choice>
    <xs:choice><xs:element name="e" type="xs:string"/><xs:element name="f" type="xs:string" minOccurs="0"/></xs:choice>
    <xs:any/>
    <other xmlns="urn:other"/>
  </xs:sequence>
  <xs:attribute name="id" type="xs:string"/>
  <xs:attribute ref="tns:globalAttr"/><xs:attribute ref="tns:missingAttr"/>
  <xs:attribute name="gone" use="prohibited"/>
  <xs:attribute name="inl"><xs:simpleType><xs:restriction base="xs:int"/></xs:simpleType></xs:attribute>
  <xs:attribute name="plain" default="x" fixed="x"/>
  <xs:attributeGroup ref="tns:Meta"/><xs:attributeGroup ref="tns:NoGroup"/>
</xs:complexType></xs:element>`
	r := conv(t, oneOp(types, "tns:Req"))
	s := AsMap(at(r.Document, "components", "schemas"))
	props := AsMap(at(s, "Req", "properties"))
	for _, k := range []string{"by", "Shared", "@id", "globalAttr", "inl", "plain", "lang", "a", "e"} {
		if !props.Has(k) {
			t.Errorf("missing property %s", k)
		}
	}
	if props.Has("gone") || at(s, "Same", "$ref") != "#/components/schemas/Base" ||
		len(AsList(at(s, "More", "allOf"))) != 2 || at(s, "More", "description") != "more stuff" ||
		!eq(s.Get("Empty"), M("type", "object")) || !eq(at(s, "Measure", "required"), []any{"value", "unit"}) ||
		!eq(s.Get("EmptySimple"), M("type", "object")) || !eq(at(s, "Pair", "properties", "v", "minItems"), 2) ||
		at(props, "plain", "const") != "x" ||
		!eq(at(s, "Req", "oneOf"), []any{M("required", []any{"c"}), M("required", []any{"d"})}) {
		t.Error(specio_dump(s))
	}
	w := strings.Join(r.Warnings, " ")
	for _, frag := range []string{"Repeating", "Mixed", "simpleContent", "DoesNotExist"} {
		if !strings.Contains(w, frag) {
			t.Error("missing warning", frag)
		}
	}
}

func TestChoicesNamedTypesAndUnknowns(t *testing.T) {
	two := `<xs:element name="Req"><xs:complexType><xs:sequence>
<xs:choice><xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:string"/></xs:choice>
<xs:choice><xs:element name="c" type="xs:string"/><xs:element name="d" type="xs:string"/></xs:choice>
</xs:sequence></xs:complexType></xs:element>`
	if len(AsList(at(schemasOf(t, oneOp(two, "tns:Req")), "Req", "allOf"))) != 2 {
		t.Error("allOf")
	}
	types := `<xs:complexType name="T"><xs:sequence><xs:element name="x" type="xs:int"/></xs:sequence></xs:complexType>
<xs:element name="Req" type="tns:T"/><xs:element name="Nil" type="tns:T" nillable="true"/>`
	d := conv(t, oneOp(types, "tns:Req")).Document
	if !eq(at(d, "paths", "/do", "post", "requestBody", "content", "application/json", "schema"), M("$ref", "#/components/schemas/T")) ||
		!AsMap(at(d, "paths", "/do", "post", "responses")).Has("202") {
		t.Error(specio_dump(d))
	}
	if !AsMap(schemasOf(t, oneOp(types, "tns:Nil")).Get("Nil")).Has("anyOf") {
		t.Error("nil")
	}
	if !strings.Contains(strings.Join(conv(t, oneOp("", "tns:Missing")).Warnings, " "), "Missing") {
		t.Error("missing element warning")
	}
	r := conv(t, wsdl(`<portType name="P"><operation name="Do"><input message="tns:Nope"/>
<output message="tns:Empty"/></operation></portType><message name="Empty"/>`, ""))
	if !AsMap(at(r.Document, "paths", "/do", "post", "responses")).Has("204") ||
		!strings.Contains(strings.Join(r.Warnings, " "), "Nope") {
		t.Error(r.Warnings)
	}
}

func TestPartsFaultsAndBindings(t *testing.T) {
	types := `<xs:element name="A" type="xs:string"/><xs:element name="F1" type="xs:string"/><xs:element name="F2" type="xs:string"/>`
	body := `<message name="In"><part name="a" element="tns:A"/><part name="b" type="xs:int"/></message>
<message name="F1"><part name="f" element="tns:F1"/></message><message name="F2"><part name="f" element="tns:F2"/></message>
<portType name="P"><operation name="Do"><input message="tns:In"/><output message="tns:In"/>
<fault name="F1" message="tns:F1"/><fault name="F2" message="tns:F2"/></operation>
<operation name="Notify"><input message="tns:In"/></operation></portType>`
	d := conv(t, wsdl(body, types)).Document
	if !eq(at(d, "components", "schemas", "DoRequest", "required"), []any{"a", "b"}) ||
		len(AsList(at(d, "paths", "/do", "post", "responses", "500", "content", "application/json", "schema", "allOf", "1", "properties", "detail", "oneOf"))) != 2 {
		t.Error(specio_dump(d))
	}
	services := `<message name="M"/>
<portType name="One"><operation name="Do"><input message="tns:M"/></operation></portType>
<portType name="Two"><operation name="Do"><input message="tns:M"/></operation><operation name="do"><input message="tns:M"/></operation></portType>
<portType name="Three"><operation name="X"><input message="tns:M"/></operation></portType>
<binding name="B12" type="tns:One"><soap12:binding style="rpc"/><operation name="Do"><soap12:operation soapAction="" style="document"/><input><soap12:body use="literal"/></input></operation></binding>
<binding name="B11" type="tns:One"><soap:binding/></binding>
<binding name="BTwo" type="tns:Two"><soap:binding/></binding>
<binding name="Http" type="tns:Three"><http:binding verb="GET"/></binding>
<binding name="NoType"/>
<service name="S">
<port name="p12" binding="tns:B12"><soap12:address location="https://a.example.com"/></port>
<port name="p11" binding="tns:B11"><soap:address location="https://a.example.com"/></port>
<port name="ph" binding="tns:Http"/><port name="pm" binding="tns:Missing"/><port name="pn"/>
</service>`
	r := conv(t, wsdl(services, ""))
	paths := AsMap(r.Document.Get("paths"))
	if !eq(paths.Keys(), []any{"/do", "/two/do", "/two/do-2", "/x"}) && strings.Join(paths.Keys(), ",") != "/do,/two/do,/two/do-2,/x" {
		t.Error(paths.Keys())
	}
	if at(paths, "/do", "post", "x-soap", "port") != "p11" {
		t.Error("prefers soap 1.1")
	}
	w := strings.Join(r.Warnings, " ")
	if !strings.Contains(w, "non-SOAP") || !strings.Contains(w, "binding not found") {
		t.Error(w)
	}
	soap12 := `<message name="M"/><portType name="One"><operation name="Do"><input message="tns:M"/></operation></portType>
<binding name="B12" type="tns:One"><soap12:binding style="document"/></binding>`
	if at(conv(t, wsdl(soap12, "")).Document, "paths", "/do", "post", "x-soap", "soapVersion") != "1.2" {
		t.Error("soap12")
	}
}

func TestSoapencArraysCollisionsAndDepth(t *testing.T) {
	enc := `xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/"`
	types := `<xs:complexType name="ArrSeq" ` + enc + `><xs:complexContent><xs:restriction base="soapenc:Array"><xs:sequence>
<xs:element name="item" type="xs:int" maxOccurs="unbounded"/></xs:sequence></xs:restriction></xs:complexContent></xs:complexType>
<xs:complexType name="ArrBare" ` + enc + `><xs:complexContent><xs:restriction base="soapenc:Array"/></xs:complexContent></xs:complexType>
<xs:element name="Req"><xs:complexType><xs:sequence><xs:element name="a" type="tns:ArrSeq"/><xs:element name="b" type="tns:ArrBare"/>
<xs:element name="c" type="soapenc:Array" ` + enc + `/></xs:sequence></xs:complexType></xs:element>`
	s := schemasOf(t, oneOp(types, "tns:Req"))
	if at(s, "ArrSeq", "items", "format") != "int32" || !eq(s.Get("ArrBare"), M("type", "array")) {
		t.Error(specio_dump(s))
	}
	coll := head + `<types><xs:schema targetNamespace="urn:t"><xs:complexType name="Item"><xs:sequence>
<xs:element name="other" type="o:Item" xmlns:o="urn:o"/></xs:sequence></xs:complexType>
<xs:element name="Req"><xs:complexType><xs:sequence><xs:element name="i" type="tns:Item"/></xs:sequence></xs:complexType></xs:element></xs:schema>
<xs:schema targetNamespace="urn:o"><xs:complexType name="Item"/></xs:schema></types>
<message name="In"><part name="p" element="tns:Req"/></message>
<portType name="P"><operation name="Do"><input message="tns:In"/></operation></portType></definitions>`
	if cs := schemasOf(t, coll); !cs.Has("Item") || !cs.Has("Item2") {
		t.Error(cs.Keys())
	}
	inner := `<xs:element name="x" type="xs:string"/>`
	for i := 0; i < 70; i++ {
		inner = `<xs:element name="n"><xs:complexType><xs:sequence>` + inner + `</xs:sequence></xs:complexType></xs:element>`
	}
	deep := `<xs:element name="Req"><xs:complexType><xs:sequence>` + inner + `</xs:sequence></xs:complexType></xs:element>`
	if !strings.Contains(strings.Join(conv(t, oneOp(deep, "tns:Req")).Warnings, " "), "deeper than 64") {
		t.Error("depth warning")
	}
	x := NewXSDConverter(func(string) {})
	if x.simple(&Elem{}, 99).Len() != 0 || x.primitive(nil, 0) != "" || x.TypeRef(nil).Len() != 0 || x.ElementRef(nil).Len() != 0 {
		t.Error("nil guards")
	}
}

func TestWSDLErrorsAndImports(t *testing.T) {
	if _, err := NewWSDLConverter("", true).Convert(`<root xmlns="urn:x"/>`); err == nil || !strings.Contains(err.Error(), "definitions") {
		t.Error(err)
	}
	if _, err := NewWSDLConverter("", true).Convert(wsdl("", "")); err == nil || !strings.Contains(err.Error(), "no portType") {
		t.Error(err)
	}
	if _, err := NewWSDLConverter("", true).Convert("<a>"); err == nil {
		t.Error("bad xml")
	}
	imp := wsdl(`<import location="other.wsdl" namespace="urn:o"/><message name="M"/><portType name="P"><operation name="Do"><input message="tns:M"/></operation></portType>`, "")
	if !strings.Contains(strings.Join(conv(t, imp).Warnings, " "), "wsdl:import") {
		t.Error("wsdl:import warning")
	}
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "types.xsd"), []byte(`<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:t">`+
		`<xs:include schemaLocation="types.xsd"/><xs:element name="Req" type="xs:string"/></xs:schema>`), 0o600)
	_ = os.WriteFile(filepath.Join(dir, "broken.xsd"), []byte(`<xs:schema`), 0o600)
	text := oneOp(`<xs:import namespace="urn:t" schemaLocation="types.xsd"/><xs:import namespace="urn:t" schemaLocation="missing.xsd"/>`, "tns:Req")
	r, err := NewWSDLConverter(dir, true).Convert(text)
	if err != nil || !eq(at(r.Document, "paths", "/do", "post", "requestBody", "content", "application/json", "schema"), M("type", "string")) ||
		!strings.Contains(strings.Join(r.Warnings, " "), "missing.xsd") {
		t.Error(err, r.Warnings)
	}
	if r, _ := NewWSDLConverter(dir, false).Convert(text); !strings.Contains(strings.Join(r.Warnings, " "), "imports disabled") {
		t.Error("disabled")
	}
	if _, err := NewWSDLConverter(dir, true).Convert(oneOp(`<xs:import schemaLocation="broken.xsd"/>`, "xs:string")); err == nil {
		t.Error("broken import accepted")
	}
	_ = os.WriteFile(filepath.Join(dir, "a.xsd"), []byte(`<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>`), 0o600)
	_ = os.WriteFile(filepath.Join(dir, "b.xsd"), []byte(`<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>`), 0o600)
	two := oneOp(`<xs:import schemaLocation="a.xsd"/><xs:import schemaLocation="b.xsd"/>`, "xs:string")
	MaxImportedDocs = 1
	_, err = NewWSDLConverter(dir, true).Convert(two)
	MaxImportedDocs = 50
	if !IsKind(err, ErrSecurity) {
		t.Error(err)
	}
	MaxImportBytes = 1
	_, err = NewWSDLConverter(dir, true).Convert(two)
	MaxImportBytes = 10 << 20
	if !IsKind(err, ErrSecurity) {
		t.Error(err)
	}
}

func specio_dump(m *Map) string { //nolint:revive
	out, _ := m.MarshalJSON()
	return string(out)
}
