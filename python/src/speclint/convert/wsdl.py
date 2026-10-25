"""WSDL 1.1 (SOAP 1.1/1.2) -> OpenAPI 3.1 converter.

The output describes a **JSON/REST facade** for the SOAP service, which is the contract an API
gateway or a strangler-fig migration exposes:

* each ``portType`` operation becomes ``POST /{operation-in-kebab-case}``
* the input message becomes the JSON request body, the output message the ``200`` response
* every operation gets a ``500`` response with a ``SoapFault`` body (plus declared fault details)
* the original SOAP details (action, style, endpoint) are kept under ``x-soap``, so a gateway can
  route back to the backend

Wire names (element and attribute names) are kept as-is. Component names are PascalCase.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from xml.etree.ElementTree import Element

from speclint.convert import ConversionResult
from speclint.convert.xmlsafe import QName, XmlDoc, parse_xml, split_tag
from speclint.convert.xsd import XSD_NS, XsdConverter, documentation
from speclint.detect import SpecKind
from speclint.errors import InputError, SecurityError
from speclint.io import DEFAULT_MAX_BYTES
from speclint.naming import to_camel, to_kebab, unique

WSDL_NS = "http://schemas.xmlsoap.org/wsdl/"
SOAP11_NS = "http://schemas.xmlsoap.org/wsdl/soap/"
SOAP12_NS = "http://schemas.xmlsoap.org/wsdl/soap12/"
MAX_IMPORTED_DOCS = 50

SOAP_FAULT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "description": "SOAP Fault translated to JSON.",
    "required": ["faultcode", "faultstring"],
    "properties": {
        "faultcode": {"type": "string", "examples": ["soap:Server"]},
        "faultstring": {"type": "string"},
        "faultactor": {"type": "string"},
        "detail": {"description": "Application-specific fault detail."},
    },
}


def _w(local: str) -> str:
    return f"{{{WSDL_NS}}}{local}"


@dataclass
class _Part:
    name: str
    element: QName | None
    type: QName | None


@dataclass
class _Operation:
    name: str
    doc: str | None
    input: QName | None
    output: QName | None
    faults: list[tuple[str, QName]] = field(default_factory=list)


@dataclass
class _BindingOp:
    soap_action: str | None
    style: str | None
    use: str | None


@dataclass
class _Binding:
    name: str
    port_type: QName
    soap_version: str | None  # "1.1", "1.2" or None for non-SOAP bindings
    style: str
    ops: dict[str, _BindingOp]


@dataclass
class _Port:
    service: str
    service_doc: str | None
    name: str
    binding: QName
    address: str | None


class _WsdlConverter:
    def __init__(self, base_dir: Path | None, allow_local_imports: bool) -> None:
        self.warnings: list[str] = []
        self.base_dir = base_dir.resolve() if base_dir else None
        self.allow_local_imports = allow_local_imports
        self.xsd = XsdConverter(self.warnings.append)
        self.messages: dict[QName, list[_Part]] = {}
        self.port_types: dict[QName, list[_Operation]] = {}
        self.bindings: dict[QName, _Binding] = {}
        self.ports: list[_Port] = []
        self._loaded: set[Path] = set()

    # ----------------------------------------------------------------- loading
    def _load_schemas(self, doc: XmlDoc, container: Element) -> None:
        for schema in container.iter(f"{{{XSD_NS}}}schema"):
            self.xsd.add_schema(schema, doc)
            for imp in list(schema.iter(f"{{{XSD_NS}}}import")) + list(
                schema.iter(f"{{{XSD_NS}}}include")
            ):
                loc = imp.get("schemaLocation")
                if loc:
                    self._load_external_schema(loc)

    def _load_external_schema(self, location: str) -> None:
        if "://" in location or location.startswith("//"):
            self.warnings.append(
                f"Remote schema import '{location}' was not fetched (remote fetching is disabled)"
            )
            return
        if not self.allow_local_imports or self.base_dir is None:
            self.warnings.append(f"Local schema import '{location}' skipped (imports disabled)")
            return
        target = (self.base_dir / location).resolve()
        if not target.is_relative_to(self.base_dir):
            raise SecurityError(f"Schema import '{location}' escapes the input directory")
        if target in self._loaded:
            return
        if len(self._loaded) >= MAX_IMPORTED_DOCS:
            raise SecurityError(f"More than {MAX_IMPORTED_DOCS} imported schema documents")
        if not target.is_file():
            self.warnings.append(f"Schema import '{location}' not found; skipped")
            return
        if target.stat().st_size > DEFAULT_MAX_BYTES:
            raise SecurityError(f"Imported schema '{location}' exceeds the size limit")
        self._loaded.add(target)
        sub = parse_xml(target.read_text(encoding="utf-8-sig"))
        self._load_schemas(sub, sub.root)

    def _index(self, doc: XmlDoc) -> None:
        root = doc.root
        tns = root.get("targetNamespace", "")
        if root.tag != _w("definitions"):
            raise InputError("Not a WSDL 1.1 document: root element must be wsdl:definitions")

        for imp in root.findall(_w("import")):
            self.warnings.append(
                f"wsdl:import of '{imp.get('location')}' is not supported; inline it instead"
            )
        types = root.find(_w("types"))
        if types is not None:
            self._load_schemas(doc, types)

        for msg in root.findall(_w("message")):
            parts = [
                _Part(
                    name=p.get("name", "part"),
                    element=doc.resolve(p, p.get("element")),
                    type=doc.resolve(p, p.get("type")),
                )
                for p in msg.findall(_w("part"))
            ]
            self.messages[(tns, msg.get("name", ""))] = parts

        for pt in root.findall(_w("portType")):
            ops: list[_Operation] = []
            for op in pt.findall(_w("operation")):
                inp, out = op.find(_w("input")), op.find(_w("output"))
                ops.append(
                    _Operation(
                        name=op.get("name", "operation"),
                        doc=documentation(op),
                        input=doc.resolve(inp, inp.get("message")) if inp is not None else None,
                        output=doc.resolve(out, out.get("message")) if out is not None else None,
                        faults=[
                            (f.get("name", "fault"), q)
                            for f in op.findall(_w("fault"))
                            if (q := doc.resolve(f, f.get("message"))) is not None
                        ],
                    )
                )
            self.port_types[(tns, pt.get("name", ""))] = ops

        for b in root.findall(_w("binding")):
            pt_qn = doc.resolve(b, b.get("type"))
            if pt_qn is None:
                continue
            version, style = None, "document"
            for ns, ver in ((SOAP11_NS, "1.1"), (SOAP12_NS, "1.2")):
                sb = b.find(f"{{{ns}}}binding")
                if sb is not None:
                    version, style = ver, sb.get("style", "document")
            bops: dict[str, _BindingOp] = {}
            for bop in b.findall(_w("operation")):
                action = op_style = use = None
                for child in bop.iter():
                    ns, local = split_tag(child.tag)
                    if ns not in (SOAP11_NS, SOAP12_NS):
                        continue
                    if local == "operation":
                        action = child.get("soapAction") or None
                        op_style = child.get("style")
                    elif local == "body" and use is None:
                        use = child.get("use")
                bops[bop.get("name", "")] = _BindingOp(action, op_style, use)
            self.bindings[(tns, b.get("name", ""))] = _Binding(
                b.get("name", ""), pt_qn, version, style, bops
            )

        for svc in root.findall(_w("service")):
            for port in svc.findall(_w("port")):
                address = None
                for ns in (SOAP11_NS, SOAP12_NS):
                    addr = port.find(f"{{{ns}}}address")
                    if addr is not None:
                        address = addr.get("location")
                b_qn = doc.resolve(port, port.get("binding"))
                if b_qn is not None:
                    self.ports.append(
                        _Port(
                            svc.get("name", "Service"),
                            documentation(svc),
                            port.get("name", ""),
                            b_qn,
                            address,
                        )
                    )

    # -------------------------------------------------------------- selection
    def _selected(self) -> list[tuple[_Port | None, _Binding | None, QName]]:
        """One (port, binding, portType) per portType, preferring SOAP 1.1 bindings."""
        chosen: dict[QName, tuple[_Port | None, _Binding | None, QName]] = {}
        rank = {"1.1": 0, "1.2": 1}
        for port in self.ports:
            binding = self.bindings.get(port.binding)
            if binding is None:
                self.warnings.append(f"Port {port.name}: binding not found")
                continue
            if binding.soap_version is None:
                self.warnings.append(f"Port {port.name}: non-SOAP binding skipped")
                continue
            current = chosen.get(binding.port_type)
            if current is None or (
                current[1] is not None
                and rank[binding.soap_version] < rank[current[1].soap_version or "1.2"]
            ):
                chosen[binding.port_type] = (port, binding, binding.port_type)
        # Abstract WSDLs (no services): fall back to bindings, then bare portTypes.
        for binding in self.bindings.values():
            if binding.soap_version and binding.port_type not in chosen:
                chosen[binding.port_type] = (None, binding, binding.port_type)
        for pt in self.port_types:
            chosen.setdefault(pt, (None, None, pt))
        return list(chosen.values())

    # ------------------------------------------------------------- operations
    def _message_schema(self, msg_qn: QName | None, style: str, wrapper: str) -> Any | None:
        if msg_qn is None:
            return None
        parts = self.messages.get(msg_qn)
        if parts is None:
            self.warnings.append(f"Message {msg_qn[1]} not found")
            return None
        if not parts:
            return None
        if style == "document" and len(parts) == 1 and parts[0].element:
            return self.xsd.element_ref(parts[0].element)
        props: dict[str, Any] = {}
        for part in parts:
            props[part.name] = (
                self.xsd.element_ref(part.element) if part.element else self.xsd.type_ref(part.type)
            )
        schema = {"type": "object", "required": list(props), "properties": props}
        return self.xsd.ref(self.xsd.new_component(wrapper, schema))

    def convert(self, text: str) -> ConversionResult:
        doc = parse_xml(text)
        self._index(doc)
        root = doc.root

        paths: dict[str, Any] = {}
        tags: dict[str, dict[str, Any]] = {}
        op_ids: set[str] = set()
        addresses: list[str] = []
        uses_fault = False
        warned_encoded = False

        selected = self._selected()
        if not selected:
            raise InputError("WSDL defines no portType operations to convert")

        for port, binding, pt_qn in selected:
            tag = port.service if port and len(selected) == 1 else pt_qn[1]
            tag_obj: dict[str, Any] = {"name": tag}
            if port and port.service_doc:
                tag_obj["description"] = port.service_doc
            tags.setdefault(tag, tag_obj)
            if port and port.address and port.address not in addresses:
                addresses.append(port.address)

            for op in self.port_types.get(pt_qn, []):
                bop = binding.ops.get(op.name) if binding else None
                style = (bop.style if bop and bop.style else None) or (
                    binding.style if binding else "document"
                )
                if bop and bop.use == "encoded" and not warned_encoded:
                    warned_encoded = True
                    self.warnings.append(
                        "rpc/encoded binding detected (not WS-I compliant); mapped as literal"
                    )
                path = "/" + to_kebab(op.name)
                if path in paths:
                    path = f"/{to_kebab(pt_qn[1])}/{to_kebab(op.name)}"
                base_path, n = path, 2
                while path in paths:
                    path, n = f"{base_path}-{n}", n + 1
                op_id = unique(to_camel(op.name), op_ids)
                op_ids.add(op_id)

                operation: dict[str, Any] = {
                    "tags": [tag],
                    "summary": op.name,
                    "operationId": op_id,
                }
                if op.doc:
                    operation["description"] = op.doc
                req = self._message_schema(op.input, style, f"{op.name}Request")
                if req is not None:
                    operation["requestBody"] = {
                        "required": True,
                        "content": {"application/json": {"schema": req}},
                    }
                responses: dict[str, Any] = {}
                resp = self._message_schema(op.output, style, f"{op.name}Response")
                if op.output is None:
                    responses["202"] = {"description": "Accepted (one-way operation)"}
                elif resp is not None:
                    responses["200"] = {
                        "description": "Successful response",
                        "content": {"application/json": {"schema": resp}},
                    }
                else:
                    responses["204"] = {"description": "Successful response (empty)"}

                uses_fault = True
                fault_schema: dict[str, Any] = {"$ref": "#/components/schemas/SoapFault"}
                details = [
                    s
                    for name, qn in op.faults
                    if (s := self._message_schema(qn, "document", f"{name}Detail")) is not None
                ]
                if details:
                    detail = details[0] if len(details) == 1 else {"oneOf": details}
                    fault_schema = {"allOf": [fault_schema, {"properties": {"detail": detail}}]}
                responses["500"] = {
                    "description": "SOAP Fault",
                    "content": {"application/json": {"schema": fault_schema}},
                }
                operation["responses"] = responses

                soap: dict[str, Any] = {"operation": op.name, "style": style}
                if bop and bop.soap_action:
                    soap["soapAction"] = bop.soap_action
                if binding:
                    soap["binding"] = binding.name
                    soap["soapVersion"] = binding.soap_version
                if port:
                    soap["service"], soap["port"] = port.service, port.name
                operation["x-soap"] = soap
                paths[path] = {"post": operation}

        schemas = dict(self.xsd.components)
        if uses_fault:
            schemas["SoapFault"] = SOAP_FAULT_SCHEMA

        title = next((p.service for p, _, _ in selected if p), None) or root.get("name") or "SOAP"
        description = documentation(root) or ""
        note = "Generated by spec-lint from a WSDL 1.1 contract (JSON facade over SOAP)."
        info: dict[str, Any] = {
            "title": title,
            "version": "1.0.0",
            "description": f"{description}\n\n{note}".strip(),
        }
        self.warnings.append("WSDL has no API version; info.version set to 1.0.0")

        out: dict[str, Any] = {"openapi": "3.1.0", "info": info}
        server: dict[str, Any] = {
            "url": "/",
            "description": "REST facade (deploy behind an API gateway that calls the SOAP backend)",
        }
        if addresses:
            server["x-soap-addresses"] = addresses
        out["servers"] = [server]
        out["tags"] = list(tags.values())
        out["paths"] = paths
        out["components"] = {"schemas": schemas}
        return ConversionResult(document=out, source_kind=SpecKind.WSDL11, warnings=self.warnings)


def convert_wsdl(
    text: str, *, base_dir: Path | None = None, allow_local_imports: bool = True
) -> ConversionResult:
    """Convert WSDL 1.1 text to an OpenAPI 3.1 document."""
    return _WsdlConverter(base_dir, allow_local_imports).convert(text)
