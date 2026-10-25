package convert

import (
	"encoding/xml"
	"errors"
	"io"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

// QName is a resolved XML qualified name.
type QName struct{ NS, Local string }

// Elem is a minimal DOM node that also remembers the in-scope namespace prefixes, which are
// needed to resolve QName-valued attributes such as type="tns:Forecast".
type Elem struct {
	NS, Local string
	Attrs     []xml.Attr
	Children  []*Elem
	Text      strings.Builder
	scope     map[string]string
}

// Attr returns an attribute by local name (unqualified attributes only).
func (e *Elem) Attr(name string) (string, bool) {
	for _, a := range e.Attrs {
		if a.Name.Space == "" && a.Name.Local == name {
			return a.Value, true
		}
	}
	return "", false
}

// AttrOr returns the attribute value or a fallback.
func (e *Elem) AttrOr(name, fallback string) string {
	if v, ok := e.Attr(name); ok {
		return v
	}
	return fallback
}

// AttrNS returns a namespace-qualified attribute.
func (e *Elem) AttrNS(ns, name string) (string, bool) {
	for _, a := range e.Attrs {
		if a.Name.Space == ns && a.Name.Local == name {
			return a.Value, true
		}
	}
	return "", false
}

// Is reports whether the element has the given namespace and local name.
func (e *Elem) Is(ns, local string) bool { return e != nil && e.NS == ns && e.Local == local }

// Kids returns direct children with the given name.
func (e *Elem) Kids(ns, local string) []*Elem {
	var out []*Elem
	for _, c := range e.Children {
		if c.Is(ns, local) {
			out = append(out, c)
		}
	}
	return out
}

// Kid returns the first direct child with the given name, or nil.
func (e *Elem) Kid(ns, local string) *Elem {
	for _, c := range e.Children {
		if c.Is(ns, local) {
			return c
		}
	}
	return nil
}

// Descendants returns all descendants (excluding e) in document order; ns=="" and local==""
// match everything.
func (e *Elem) Descendants(ns, local string) []*Elem {
	var out []*Elem
	var walk func(*Elem)
	walk = func(n *Elem) {
		for _, c := range n.Children {
			if (ns == "" && local == "") || c.Is(ns, local) {
				out = append(out, c)
			}
			walk(c)
		}
	}
	walk(e)
	return out
}

// TextContent is the concatenated text of the element and its descendants.
func (e *Elem) TextContent() string {
	var sb strings.Builder
	var walk func(*Elem)
	walk = func(n *Elem) {
		sb.WriteString(n.Text.String())
		for _, c := range n.Children {
			walk(c)
		}
	}
	walk(e)
	return sb.String()
}

// Resolve resolves a QName attribute value ("tns:Foo" or "Foo") in e's scope.
func (e *Elem) Resolve(value string) *QName {
	if value == "" {
		return nil
	}
	prefix, local := "", value
	if i := strings.LastIndex(value, ":"); i >= 0 {
		prefix, local = value[:i], value[i+1:]
	}
	return &QName{NS: e.scope[prefix], Local: local}
}

// ResolveAttr resolves the QName held in attribute name, or nil when absent.
func (e *Elem) ResolveAttr(name string) *QName {
	v, ok := e.Attr(name)
	if !ok {
		return nil
	}
	return e.Resolve(v)
}

// ParseXML parses text into an element tree. Any DOCTYPE is rejected outright (blocking XXE and
// entity-expansion attacks); Go's encoding/xml never fetches external entities anyway.
func ParseXML(text string) (*Elem, error) {
	dec := xml.NewDecoder(strings.NewReader(text))
	dec.Strict = true
	dec.CharsetReader = func(label string, r io.Reader) (io.Reader, error) {
		if strings.EqualFold(label, "utf-8") || strings.EqualFold(label, "us-ascii") {
			return r, nil
		}
		return nil, errors.New("unsupported encoding " + label)
	}
	var root *Elem
	var stack []*Elem
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, doc.Errorf(doc.ErrInput, "Invalid XML: %v", err)
		}
		switch t := tok.(type) {
		case xml.Directive:
			if strings.HasPrefix(strings.ToUpper(strings.TrimSpace(string(t))), "DOCTYPE") {
				return nil, doc.Errorf(doc.ErrSecurity,
					"Unsafe XML rejected: DTD/entity declarations are not allowed")
			}
		case xml.StartElement:
			scope := map[string]string{}
			if len(stack) > 0 {
				for k, v := range stack[len(stack)-1].scope {
					scope[k] = v
				}
			}
			var attrs []xml.Attr
			for _, a := range t.Attr {
				switch {
				case a.Name.Space == "xmlns":
					scope[a.Name.Local] = a.Value
				case a.Name.Space == "" && a.Name.Local == "xmlns":
					scope[""] = a.Value
				default:
					attrs = append(attrs, a)
				}
			}
			el := &Elem{NS: t.Name.Space, Local: t.Name.Local, Attrs: attrs, scope: scope}
			if len(stack) > 0 {
				parent := stack[len(stack)-1]
				parent.Children = append(parent.Children, el)
			} else if root == nil {
				root = el
			}
			stack = append(stack, el)
		case xml.EndElement:
			stack = stack[:len(stack)-1]
		case xml.CharData:
			if len(stack) > 0 {
				stack[len(stack)-1].Text.Write(t)
			}
		}
	}
	if root == nil {
		return nil, doc.Errorf(doc.ErrInput, "Invalid XML: empty document")
	}
	return root, nil
}
