"""Hardened XML parsing that also keeps namespace-prefix scope for every element.

``xml.etree`` drops ``xmlns:*`` declarations after parsing, but WSDL/XSD attribute values are
QNames (``type="tns:Forecast"``) that need them. We use ``iterparse`` to record the in-scope
prefix map of each element.

``defusedxml`` rejects DTDs, entity declarations and external references, which blocks XXE
(file disclosure / SSRF) and entity-expansion denial of service.
"""

from __future__ import annotations

import io
from dataclasses import dataclass, field
from xml.etree.ElementTree import Element

from defusedxml import DefusedXmlException
from defusedxml.ElementTree import ParseError, iterparse

from speclint.errors import InputError, SecurityError

QName = tuple[str, str]  # (namespace URI, local name)


@dataclass
class XmlDoc:
    root: Element
    nsmaps: dict[int, dict[str, str]] = field(default_factory=dict)

    def nsmap(self, elem: Element) -> dict[str, str]:
        return self.nsmaps.get(id(elem), {})

    def resolve(self, elem: Element, value: str | None) -> QName | None:
        """Resolve a QName attribute value (``tns:Foo`` / ``Foo``) in ``elem``'s scope."""
        if not value:
            return None
        prefix, _, local = value.rpartition(":")
        ns = self.nsmap(elem).get(prefix, "")
        return (ns, local)


def split_tag(tag: str) -> QName:
    if tag.startswith("{"):
        ns, _, local = tag[1:].partition("}")
        return ns, local
    return "", tag


def parse_xml(text: str) -> XmlDoc:
    data = text.encode("utf-8")
    nsmaps: dict[int, dict[str, str]] = {}
    stack: list[dict[str, str]] = [{"xml": "http://www.w3.org/XML/1998/namespace"}]
    pending: dict[str, str] = {}
    root: Element | None = None
    try:
        for event, item in iterparse(
            io.BytesIO(data),
            events=("start-ns", "start", "end"),
            forbid_dtd=True,
            forbid_entities=True,
            forbid_external=True,
        ):
            if event == "start-ns":
                prefix, uri = item
                pending[prefix or ""] = uri
            elif event == "start":
                scope = {**stack[-1], **pending} if pending else stack[-1]
                pending = {}
                stack.append(scope)
                nsmaps[id(item)] = scope
                if root is None:
                    root = item
            else:
                stack.pop()
    except DefusedXmlException as exc:
        raise SecurityError(f"Unsafe XML rejected: {exc.__class__.__name__}") from exc
    except ParseError as exc:
        raise InputError(f"Invalid XML: {exc}") from exc
    if root is None:  # pragma: no cover - iterparse raises on empty input
        raise InputError("Empty XML document")
    return XmlDoc(root=root, nsmaps=nsmaps)
