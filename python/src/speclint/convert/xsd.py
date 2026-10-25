"""XML Schema (XSD 1.0) -> JSON Schema 2020-12 mapping, as used by OpenAPI 3.1.

Named types become ``components/schemas`` entries and are emitted lazily, so only types that
are actually reachable from an operation appear in the output (no unused components).

See ``docs/wsdl-mapping.md`` for the full mapping table and known limitations.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any
from xml.etree.ElementTree import Element

from speclint.convert.xmlsafe import QName, XmlDoc, split_tag
from speclint.naming import to_pascal, unique

XSD_NS = "http://www.w3.org/2001/XMLSchema"
SOAPENC_NS = "http://schemas.xmlsoap.org/soap/encoding/"
WSDL_NS = "http://schemas.xmlsoap.org/wsdl/"

MAX_DEPTH = 64
_COMPONENT_NAME = re.compile(r"[^A-Za-z0-9._-]")


def _s(**kw: Any) -> dict[str, Any]:
    return dict(kw)


BUILTINS: dict[str, dict[str, Any]] = {
    **{
        n: _s(type="string")
        for n in (
            "string",
            "normalizedString",
            "token",
            "language",
            "Name",
            "NCName",
            "ID",
            "IDREF",
            "IDREFS",
            "ENTITY",
            "ENTITIES",
            "NMTOKEN",
            "NMTOKENS",
            "QName",
            "NOTATION",
            "gYear",
            "gYearMonth",
            "gMonth",
            "gMonthDay",
            "gDay",
        )
    },
    "anyURI": _s(type="string", format="uri"),
    "boolean": _s(type="boolean"),
    "decimal": _s(type="number"),
    "float": _s(type="number", format="float"),
    "double": _s(type="number", format="double"),
    "integer": _s(type="integer"),
    "int": _s(type="integer", format="int32"),
    "long": _s(type="integer", format="int64"),
    "short": _s(type="integer", minimum=-32768, maximum=32767),
    "byte": _s(type="integer", minimum=-128, maximum=127),
    "nonNegativeInteger": _s(type="integer", minimum=0),
    "positiveInteger": _s(type="integer", minimum=1),
    "nonPositiveInteger": _s(type="integer", maximum=0),
    "negativeInteger": _s(type="integer", maximum=-1),
    "unsignedLong": _s(type="integer", format="int64", minimum=0),
    "unsignedInt": _s(type="integer", format="int64", minimum=0, maximum=4294967295),
    "unsignedShort": _s(type="integer", minimum=0, maximum=65535),
    "unsignedByte": _s(type="integer", minimum=0, maximum=255),
    "date": _s(type="string", format="date"),
    "dateTime": _s(type="string", format="date-time"),
    "time": _s(type="string", format="time"),
    "duration": _s(type="string", format="duration"),
    "base64Binary": _s(type="string", contentEncoding="base64"),
    "hexBinary": _s(type="string", pattern="^([0-9a-fA-F]{2})*$"),
    "anyType": {},
    "anySimpleType": {},
}

_NUMERIC_FACETS = {
    "minInclusive": "minimum",
    "maxInclusive": "maximum",
    "minExclusive": "exclusiveMinimum",
    "maxExclusive": "exclusiveMaximum",
}
_LENGTH_FACETS = {"minLength": "minLength", "maxLength": "maxLength"}


def _x(local: str) -> str:
    return f"{{{XSD_NS}}}{local}"


def _children(elem: Element, *locals_: str) -> list[Element]:
    wanted = {_x(n) for n in locals_}
    return [c for c in elem if c.tag in wanted]


def _child(elem: Element, local: str) -> Element | None:
    return elem.find(_x(local))


def documentation(elem: Element) -> str | None:
    """Text of ``xsd:annotation/xsd:documentation`` or ``wsdl:documentation``."""
    for path in (f"{_x('annotation')}/{_x('documentation')}", f"{{{WSDL_NS}}}documentation"):
        node = elem.find(path)
        if node is not None:
            text = " ".join("".join(node.itertext()).split())
            if text:
                return text
    return None


def _occurs(elem: Element) -> tuple[int, int | None]:
    """Return (minOccurs, maxOccurs) where ``None`` means unbounded."""
    lo = int(elem.get("minOccurs", "1"))
    hi_raw = elem.get("maxOccurs", "1")
    hi = None if hi_raw == "unbounded" else int(hi_raw)
    return lo, hi


def _add_null(schema: dict[str, Any]) -> dict[str, Any]:
    if "type" in schema and "$ref" not in schema:
        t = schema["type"]
        schema["type"] = [*t, "null"] if isinstance(t, list) else [t, "null"]
        return schema
    return {"anyOf": [schema, {"type": "null"}]}


@dataclass
class _ObjectBuilder:
    properties: dict[str, Any] = field(default_factory=dict)
    required: list[str] = field(default_factory=list)
    choices: list[list[list[str]]] = field(default_factory=list)
    open_content: bool = False

    def add(self, name: str, schema: dict[str, Any], required: bool) -> str:
        key = name if name not in self.properties else unique(f"@{name}", self.properties)
        self.properties[key] = schema
        if required:
            self.required.append(key)
        return key

    def build(self) -> dict[str, Any]:
        out: dict[str, Any] = {"type": "object"}
        if self.properties:
            out["properties"] = self.properties
        if self.required:
            out["required"] = self.required
        groups = [
            {"oneOf": [{"required": branch} for branch in choice]}
            for choice in self.choices
            if all(choice)  # a branch with nothing required makes the choice unenforceable
        ]
        if len(groups) == 1:
            out.update(groups[0])
        elif groups:
            out["allOf"] = groups
        return out


@dataclass
class _Global:
    elem: Element
    doc: XmlDoc


class XsdConverter:
    """Converts XSD declarations to JSON Schema, collecting named components."""

    def __init__(self, warn: Callable[[str], None]) -> None:
        self.warn = warn
        self.elements: dict[QName, _Global] = {}
        self.complex_types: dict[QName, _Global] = {}
        self.simple_types: dict[QName, _Global] = {}
        self.groups: dict[QName, _Global] = {}
        self.attribute_groups: dict[QName, _Global] = {}
        self.attributes: dict[QName, _Global] = {}
        self.components: dict[str, Any] = {}
        self._names: dict[tuple[str, QName], str] = {}
        self._warned: set[str] = set()

    # ---------------------------------------------------------------- indexing
    def add_schema(self, schema: Element, doc: XmlDoc) -> None:
        tns = schema.get("targetNamespace", "")
        tables = {
            "element": self.elements,
            "complexType": self.complex_types,
            "simpleType": self.simple_types,
            "group": self.groups,
            "attributeGroup": self.attribute_groups,
            "attribute": self.attributes,
        }
        for child in schema:
            ns, local = split_tag(child.tag)
            name = child.get("name")
            if ns == XSD_NS and local in tables and name:
                tables[local].setdefault((tns, name), _Global(child, doc))

    def _warn_once(self, msg: str) -> None:
        if msg not in self._warned:
            self._warned.add(msg)
            self.warn(msg)

    # -------------------------------------------------------------- components
    def _component_name(self, kind: str, qn: QName) -> tuple[str, bool]:
        """Return (name, is_new) for a named XSD declaration."""
        key = (kind, qn)
        if key in self._names:
            return self._names[key], False
        base = to_pascal(_COMPONENT_NAME.sub("_", qn[1])) or "Schema"
        name = unique(base, self.components)
        self._names[key] = name
        self.components[name] = {}  # placeholder: supports recursive types
        return name, True

    def new_component(self, preferred: str, schema: dict[str, Any]) -> str:
        """Register a synthetic component (e.g. an RPC request wrapper)."""
        name = unique(to_pascal(preferred), self.components)
        self.components[name] = schema
        return name

    @staticmethod
    def ref(name: str) -> dict[str, Any]:
        return {"$ref": f"#/components/schemas/{name}"}

    # ------------------------------------------------------------ public API
    def type_ref(self, qn: QName | None) -> dict[str, Any]:
        """Schema for a type QName: builtin inline, named type as ``$ref``."""
        if qn is None:
            return {}
        ns, local = qn
        if ns in (XSD_NS, SOAPENC_NS) and local in BUILTINS:
            return dict(BUILTINS[local])
        if ns == SOAPENC_NS and local == "Array":
            return {"type": "array"}
        if qn in self.complex_types:
            name, new = self._component_name("type", qn)
            if new:
                g = self.complex_types[qn]
                schema = self._complex(g.elem, g.doc, 0)
                self._describe(schema, g.elem)
                self.components[name] = schema
            return self.ref(name)
        if qn in self.simple_types:
            name, new = self._component_name("type", qn)
            if new:
                g = self.simple_types[qn]
                schema = self._simple(g.elem, g.doc, 0)
                self._describe(schema, g.elem)
                self.components[name] = schema
            return self.ref(name)
        self._warn_once(f"Unknown XSD type {{{ns}}}{local}; mapped to an unconstrained schema")
        return {}

    def element_ref(self, qn: QName | None) -> dict[str, Any]:
        """Schema for a global element QName (used for document/literal message parts)."""
        if qn is None or qn not in self.elements:
            if qn is not None:
                self._warn_once(f"Unknown XSD element {{{qn[0]}}}{qn[1]}; mapped to {{}}")
            return {}
        g = self.elements[qn]
        has_inline = _child(g.elem, "complexType") is not None or (
            _child(g.elem, "simpleType") is not None
        )
        if not has_inline and g.elem.get("type") and g.elem.get("nillable") != "true":
            return self.type_ref(g.doc.resolve(g.elem, g.elem.get("type")))
        name, new = self._component_name("element", qn)
        if new:
            self.components[name] = self._element_body(g.elem, g.doc, 0)
        return self.ref(name)

    # ------------------------------------------------------------ declarations
    def _describe(self, schema: dict[str, Any], elem: Element) -> dict[str, Any]:
        doc = documentation(elem)
        if doc and "$ref" not in schema:
            schema.setdefault("description", doc)
        return schema

    def _element_body(self, el: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        """Schema of a single element occurrence (ignoring min/maxOccurs)."""
        ct, st = _child(el, "complexType"), _child(el, "simpleType")
        primitive = None
        if ct is not None:
            schema = self._complex(ct, doc, depth + 1)
        elif st is not None:
            schema = self._simple(st, doc, depth + 1)
        elif el.get("type"):
            type_qn = doc.resolve(el, el.get("type"))
            schema = self.type_ref(type_qn)
            primitive = self._primitive(type_qn)
        else:
            schema = {}
        self._apply_value_constraints(schema, el, primitive)
        if el.get("nillable") == "true":
            schema = _add_null(schema)
        return self._describe(schema, el)

    def _apply_value_constraints(
        self, schema: dict[str, Any], node: Element, primitive: str | None
    ) -> None:
        """XSD ``default`` / ``fixed`` -> JSON Schema ``default`` / ``const`` (typed)."""
        if node.get("default") is not None:
            schema["default"] = self._typed(schema, node.get("default", ""), primitive)
        if node.get("fixed") is not None:
            schema["const"] = self._typed(schema, node.get("fixed", ""), primitive)

    def _element_particle(
        self, el: Element, doc: XmlDoc, depth: int
    ) -> tuple[str, dict[str, Any], bool]:
        lo, hi = _occurs(el)
        if el.get("ref"):
            qn = doc.resolve(el, el.get("ref"))
            name = qn[1] if qn else "value"
            schema = self.element_ref(qn)
        else:
            name = el.get("name", "value")
            schema = self._element_body(el, doc, depth)
        if hi is None or hi > 1:
            arr: dict[str, Any] = {"type": "array", "items": schema}
            if lo > 1:
                arr["minItems"] = lo
            if hi is not None:
                arr["maxItems"] = hi
            schema = arr
        return name, schema, lo > 0

    def _walk(self, container: Element, doc: XmlDoc, obj: _ObjectBuilder, depth: int) -> None:
        if depth > MAX_DEPTH:
            self._warn_once("XSD nesting deeper than 64 levels was truncated")
            return
        for child in container:
            ns, local = split_tag(child.tag)
            if ns != XSD_NS:
                continue
            if local == "element":
                name, schema, req = self._element_particle(child, doc, depth)
                obj.add(name, schema, req)
            elif local in ("sequence", "all"):
                if _occurs(child)[1] != 1:
                    self._warn_once("Repeating <sequence> groups are flattened into one object")
                self._walk(child, doc, obj, depth + 1)
            elif local == "choice":
                self._choice(child, doc, obj, depth)
            elif local == "group":
                g = self.groups.get(doc.resolve(child, child.get("ref")) or ("", ""))
                if g is not None:
                    self._walk(g.elem, g.doc, obj, depth + 1)
            elif local == "attribute":
                self._attribute(child, doc, obj)
            elif local == "attributeGroup":
                g = self.attribute_groups.get(doc.resolve(child, child.get("ref")) or ("", ""))
                if g is not None:
                    self._walk(g.elem, g.doc, obj, depth + 1)
            elif local in ("any", "anyAttribute"):
                obj.open_content = True

    def _choice(self, choice: Element, doc: XmlDoc, obj: _ObjectBuilder, depth: int) -> None:
        branches: list[list[str]] = []
        for branch in choice:
            ns, local = split_tag(branch.tag)
            if ns != XSD_NS or local == "annotation":
                continue
            sub = _ObjectBuilder()
            wrapper = Element("wrapper")
            wrapper.append(branch)
            self._walk(wrapper, doc, sub, depth + 1)
            names = [obj.add(n, s, False) for n, s in sub.properties.items()]
            branches.append(
                [n for n, orig in zip(names, sub.properties, strict=True) if orig in sub.required]
            )
        if branches and choice.get("minOccurs", "1") != "0":
            obj.choices.append(branches)

    def _attribute(self, attr: Element, doc: XmlDoc, obj: _ObjectBuilder) -> None:
        if attr.get("ref"):
            qn = doc.resolve(attr, attr.get("ref"))
            g = self.attributes.get(qn) if qn else None
            if g is None:
                return
            attr, doc = g.elem, g.doc
        name = attr.get("name")
        if not name or attr.get("use") == "prohibited":
            return
        st = _child(attr, "simpleType")
        primitive = None
        if st is not None:
            schema = self._simple(st, doc, 1)
        elif attr.get("type"):
            type_qn = doc.resolve(attr, attr.get("type"))
            schema, primitive = self.type_ref(type_qn), self._primitive(type_qn)
        else:
            schema = {"type": "string"}
        self._apply_value_constraints(schema, attr, primitive)
        self._describe(schema, attr)
        obj.add(name, schema, attr.get("use") == "required")

    # ------------------------------------------------------------------ types
    def _complex(self, ct: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        if ct.get("mixed") == "true":
            self._warn_once("Mixed-content complex types: text content is not represented")
        cc, sc = _child(ct, "complexContent"), _child(ct, "simpleContent")
        if cc is not None:
            return self._complex_content(cc, doc, depth)
        if sc is not None:
            return self._simple_content(sc, doc, depth)
        obj = _ObjectBuilder()
        self._walk(ct, doc, obj, depth + 1)
        return obj.build()

    def _complex_content(self, cc: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        ext, res = _child(cc, "extension"), _child(cc, "restriction")
        node = ext if ext is not None else res
        if node is None:
            return {"type": "object"}
        base = doc.resolve(node, node.get("base"))
        if res is not None and base == (SOAPENC_NS, "Array"):
            return self._soapenc_array(res, doc, depth)
        obj = _ObjectBuilder()
        self._walk(node, doc, obj, depth + 1)
        own = obj.build()
        if ext is not None and base is not None:
            base_schema = self.type_ref(base)
            if own.get("properties"):
                return {"allOf": [base_schema, own]}
            return base_schema
        return own

    def _soapenc_array(self, res: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        """``soapenc:Array`` restriction (rpc/encoded style) -> JSON array."""
        array_type_attr = f"{{{WSDL_NS}}}arrayType"
        for attr in res.iter(_x("attribute")):
            raw = attr.get(array_type_attr)
            if raw:
                item_qn = doc.resolve(attr, raw.split("[", 1)[0])
                return {"type": "array", "items": self.type_ref(item_qn)}
        obj = _ObjectBuilder()
        self._walk(res, doc, obj, depth + 1)
        props = list(obj.properties.values())
        if len(props) == 1 and props[0].get("type") == "array":
            return dict(props[0])
        return {"type": "array"}

    def _simple_content(self, sc: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        node = _child(sc, "extension")
        if node is None:
            node = _child(sc, "restriction")
        if node is None:
            return {"type": "object"}
        obj = _ObjectBuilder()
        obj.add("value", self.type_ref(doc.resolve(node, node.get("base"))), True)
        self._walk(node, doc, obj, depth + 1)
        self._warn_once("simpleContent text is mapped to a 'value' property")
        return obj.build()

    def _simple(self, st: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        if depth > MAX_DEPTH:
            return {}
        res, lst, uni = _child(st, "restriction"), _child(st, "list"), _child(st, "union")
        if res is not None:
            return self._restriction(res, doc, depth)
        if lst is not None:
            inner = _child(lst, "simpleType")
            items = (
                self._simple(inner, doc, depth + 1)
                if inner is not None
                else self.type_ref(doc.resolve(lst, lst.get("itemType")))
            )
            self._warn_once("xsd:list (space-separated values) is mapped to a JSON array")
            return {"type": "array", "items": items}
        if uni is not None:
            members = [
                self.type_ref(doc.resolve(uni, m)) for m in (uni.get("memberTypes") or "").split()
            ]
            members += [self._simple(s, doc, depth + 1) for s in _children(uni, "simpleType")]
            return {"anyOf": members} if members else {}
        return {}

    def _primitive(self, qn: QName | None, depth: int = 0) -> str | None:
        """Follow restriction chains back to a JSON primitive type name."""
        if qn is None or depth > MAX_DEPTH:
            return None
        if qn[0] in (XSD_NS, SOAPENC_NS):
            t = BUILTINS.get(qn[1], {}).get("type")
            return t if isinstance(t, str) else None
        g = self.simple_types.get(qn)
        if g is None:
            return None
        res = _child(g.elem, "restriction")
        if res is None:
            return None
        return self._primitive(g.doc.resolve(res, res.get("base")), depth + 1)

    @staticmethod
    def _typed(schema: dict[str, Any], raw: str, primitive: str | None = None) -> Any:
        kind = primitive or schema.get("type")
        if isinstance(kind, list):
            kind = next((k for k in kind if k != "null"), None)
        try:
            if kind == "integer":
                return int(raw)
            if kind == "number":
                return float(raw) if any(c in raw for c in ".eE") else int(raw)
        except ValueError:
            return raw
        if kind == "boolean":
            return raw.strip() in ("true", "1")
        return raw

    def _restriction(self, res: Element, doc: XmlDoc, depth: int) -> dict[str, Any]:
        inner = _child(res, "simpleType")
        base_qn = doc.resolve(res, res.get("base"))
        base = self._simple(inner, doc, depth + 1) if inner is not None else self.type_ref(base_qn)
        primitive = self._primitive(base_qn) or (
            base.get("type") if isinstance(base.get("type"), str) else None
        )
        schema = dict(base)
        enum: list[Any] = []
        patterns: list[str] = []
        for facet in res:
            ns, local = split_tag(facet.tag)
            value = facet.get("value")
            if ns != XSD_NS or value is None:
                continue
            if local == "enumeration":
                enum.append(self._typed(schema, value, primitive))
            elif local == "pattern":
                patterns.append(value)
            elif local in _NUMERIC_FACETS:
                schema[_NUMERIC_FACETS[local]] = self._typed(schema, value, primitive or "number")
            elif local in _LENGTH_FACETS:
                schema[_LENGTH_FACETS[local]] = int(value)
            elif local == "length":
                schema["minLength"] = schema["maxLength"] = int(value)
        if enum:
            schema["enum"] = enum
        if patterns:
            # XSD patterns are implicitly anchored; JSON Schema patterns are not.
            schema["pattern"] = "^(?:" + "|".join(patterns) + ")$"
        return schema
