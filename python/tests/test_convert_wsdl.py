from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from speclint.convert import convert_source
from speclint.convert.wsdl import convert_wsdl
from speclint.convert.xmlsafe import parse_xml
from speclint.errors import InputError, SecurityError
from speclint.io import read_source

WSDL_HEAD = """<?xml version="1.0"?>
<definitions xmlns="http://schemas.xmlsoap.org/wsdl/"
  xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
  xmlns:soap12="http://schemas.xmlsoap.org/wsdl/soap12/"
  xmlns:http="http://schemas.xmlsoap.org/wsdl/http/"
  xmlns:xs="http://www.w3.org/2001/XMLSchema"
  xmlns:tns="urn:t" targetNamespace="urn:t" name="T">
"""


def wsdl(body: str, types: str = "") -> str:
    schema = f'<types><xs:schema targetNamespace="urn:t">{types}</xs:schema></types>'
    return WSDL_HEAD + schema + body + "</definitions>"


def one_op(types: str, input_el: str = "tns:Req", extra: str = "") -> str:
    return wsdl(
        f"""
        <message name="In"><part name="p" element="{input_el}"/></message>
        <portType name="P"><operation name="Do"><input message="tns:In"/></operation></portType>
        {extra}""",
        types,
    )


def schemas_of(text: str) -> dict[str, Any]:
    return convert_wsdl(text).document["components"]["schemas"]  # type: ignore[no-any-return]


def test_weather_service_shape(testdata: Path) -> None:
    out = convert_source(read_source(testdata / "weather/weather-service.wsdl")).document
    assert set(out["paths"]) == {"/get-current-conditions", "/get-forecast"}
    op = out["paths"]["/get-forecast"]["post"]
    assert op["operationId"] == "getForecast"
    assert op["x-soap"]["soapVersion"] == "1.1"  # SOAP 1.1 port preferred over 1.2
    schemas = out["components"]["schemas"]
    assert schemas["Latitude"] == {"type": "number", "minimum": -90, "maximum": 90}
    assert schemas["ForecastDay"]["allOf"][0] == {"$ref": "#/components/schemas/Conditions"}
    assert schemas["Conditions"]["properties"]["summary"]["type"] == ["string", "null"]
    assert schemas["GetForecastResponse"]["properties"]["day"]["type"] == "array"
    assert schemas["GetCurrentConditions"]["oneOf"] == [
        {"required": ["city"]},
        {"required": ["stationId"]},
    ]
    assert schemas["Location"]["properties"]["stationId"] == {
        "$ref": "#/components/schemas/StationId"
    }
    assert out["servers"][0]["x-soap-addresses"] == ["https://soap.example.com/weather/v1"]


def test_legacy_rpc_encoded(testdata: Path) -> None:
    result = convert_source(read_source(testdata / "weather/legacy-forecast-rpc.wsdl"))
    schemas = result.document["components"]["schemas"]
    req = schemas["GetForecastByPointRequest"]
    assert req["required"] == [
        "latitude",
        "longitude",
        "product",
        "startTime",
        "endTime",
        "weatherParameters",
    ]
    assert schemas["ArrayOfString"] == {"type": "array", "items": {"type": "string"}}
    assert any("rpc/encoded" in w for w in result.warnings)


def test_builtin_facets_and_lists() -> None:
    types = """
      <xs:simpleType name="Code"><xs:restriction base="xs:string">
        <xs:length value="3"/><xs:pattern value="[A-Z]+"/><xs:pattern value="[0-9]+"/>
      </xs:restriction></xs:simpleType>
      <xs:simpleType name="Temp"><xs:restriction base="xs:decimal">
        <xs:minExclusive value="-1.5"/><xs:maxExclusive value="60"/>
      </xs:restriction></xs:simpleType>
      <xs:simpleType name="Level"><xs:restriction base="xs:int">
        <xs:enumeration value="1"/><xs:enumeration value="2"/></xs:restriction></xs:simpleType>
      <xs:simpleType name="Child"><xs:restriction base="tns:Level"><xs:enumeration value="1"/>
      </xs:restriction></xs:simpleType>
      <xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>
      <xs:simpleType name="Inline"><xs:list><xs:simpleType><xs:restriction base="xs:int"/>
      </xs:simpleType></xs:list></xs:simpleType>
      <xs:simpleType name="Either"><xs:union memberTypes="xs:int xs:boolean">
        <xs:simpleType><xs:restriction base="xs:string"/></xs:simpleType></xs:union></xs:simpleType>
      <xs:simpleType name="Nothing"><xs:union/></xs:simpleType>
      <xs:simpleType name="Bad"><xs:restriction base="xs:int"><xs:enumeration value="x"/>
      </xs:restriction></xs:simpleType>
      <xs:simpleType name="Flag"><xs:restriction base="xs:boolean"/></xs:simpleType>
      <xs:element name="Req"><xs:complexType><xs:sequence>
        <xs:element name="code" type="tns:Code"/>
        <xs:element name="temp" type="tns:Temp"/>
        <xs:element name="level" type="tns:Level"/>
        <xs:element name="child" type="tns:Child"/>
        <xs:element name="codes" type="tns:Codes"/>
        <xs:element name="inline" type="tns:Inline"/>
        <xs:element name="either" type="tns:Either"/>
        <xs:element name="nothing" type="tns:Nothing"/>
        <xs:element name="bad" type="tns:Bad"/>
        <xs:element name="flag" type="tns:Flag" default="true"/>
        <xs:element name="fixed" type="xs:int" fixed="7"/>
        <xs:element name="ratio" type="xs:float" default="0.5"/>
        <xs:element name="any"/>
        <xs:element name="raw" type="xs:base64Binary"/>
        <xs:element name="encoded" type="soapenc:string"
            xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/"/>
      </xs:sequence></xs:complexType></xs:element>
    """
    s = schemas_of(one_op(types))
    assert s["Code"] == {
        "type": "string",
        "minLength": 3,
        "maxLength": 3,
        "pattern": "^(?:[A-Z]+|[0-9]+)$",
    }
    assert s["Temp"] == {"type": "number", "exclusiveMinimum": -1.5, "exclusiveMaximum": 60}
    assert s["Level"]["enum"] == [1, 2]
    assert s["Child"] == {"$ref": "#/components/schemas/Level", "enum": [1]}
    assert s["Codes"]["items"] == {"$ref": "#/components/schemas/Code"}
    assert s["Inline"]["items"] == {"type": "integer", "format": "int32"}
    assert len(s["Either"]["anyOf"]) == 3
    assert s["Nothing"] == {}
    assert s["Bad"]["enum"] == ["x"]
    props = s["Req"]["properties"]
    assert props["flag"]["default"] is True
    assert props["fixed"]["const"] == 7
    assert props["ratio"]["default"] == 0.5
    assert props["any"] == {}
    assert props["raw"]["contentEncoding"] == "base64"
    assert props["encoded"] == {"type": "string"}


def test_complex_features() -> None:
    types = """
      <xs:group name="Audit"><xs:sequence><xs:element name="by" type="xs:string"/></xs:sequence>
      </xs:group>
      <xs:attributeGroup name="Meta"><xs:attribute name="lang" type="xs:language"/>
      </xs:attributeGroup>
      <xs:attribute name="globalAttr" type="xs:string"/>
      <xs:complexType name="Base"><xs:sequence><xs:element name="id" type="xs:string"/>
      </xs:sequence></xs:complexType>
      <xs:complexType name="Same"><xs:complexContent><xs:extension base="tns:Base"/>
      </xs:complexContent></xs:complexType>
      <xs:complexType name="Restricted"><xs:complexContent><xs:restriction base="tns:Base">
        <xs:sequence><xs:element name="id" type="xs:string"/></xs:sequence>
      </xs:restriction></xs:complexContent></xs:complexType>
      <xs:complexType name="Empty"><xs:complexContent/></xs:complexType>
      <xs:complexType name="Measure"><xs:simpleContent><xs:extension base="xs:double">
        <xs:attribute name="unit" type="xs:string" use="required"/>
      </xs:extension></xs:simpleContent></xs:complexType>
      <xs:complexType name="EmptySimple"><xs:simpleContent/></xs:complexType>
      <xs:complexType name="Mixed" mixed="true"><xs:sequence>
        <xs:element name="b" type="xs:string"/></xs:sequence></xs:complexType>
      <xs:complexType name="Node"><xs:sequence>
        <xs:element name="child" type="tns:Node" minOccurs="0" maxOccurs="unbounded"/>
      </xs:sequence></xs:complexType>
      <xs:complexType name="Pair"><xs:sequence>
        <xs:element name="v" type="xs:int" minOccurs="2" maxOccurs="2"/></xs:sequence>
      </xs:complexType>
      <xs:element name="Shared" type="xs:string"/>
      <xs:element name="Req"><xs:complexType>
        <xs:sequence maxOccurs="unbounded">
          <xs:annotation><xs:documentation>ignored</xs:documentation></xs:annotation>
          <xs:group ref="tns:Audit"/>
          <xs:group ref="tns:Missing"/>
          <xs:element ref="tns:Shared"/>
          <xs:element name="same" type="tns:Same"/>
          <xs:element name="restricted" type="tns:Restricted"/>
          <xs:element name="empty" type="tns:Empty"/>
          <xs:element name="measure" type="tns:Measure"/>
          <xs:element name="emptySimple" type="tns:EmptySimple"/>
          <xs:element name="mixed" type="tns:Mixed"/>
          <xs:element name="node" type="tns:Node"/>
          <xs:element name="pair" type="tns:Pair"/>
          <xs:element name="unknown" type="tns:DoesNotExist"/>
          <xs:element name="id" type="xs:string"/>
          <xs:choice minOccurs="0">
            <xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:string"/>
          </xs:choice>
          <xs:choice>
            <xs:annotation/>
            <xs:sequence><xs:element name="c" type="xs:string"/></xs:sequence>
            <xs:element name="d" type="xs:string"/>
          </xs:choice>
          <xs:choice><xs:element name="e" type="xs:string"/>
            <xs:element name="f" type="xs:string" minOccurs="0"/></xs:choice>
          <xs:any/>
        </xs:sequence>
        <xs:attribute name="id" type="xs:string"/>
        <xs:attribute ref="tns:globalAttr"/>
        <xs:attribute ref="tns:missingAttr"/>
        <xs:attribute name="gone" use="prohibited"/>
        <xs:attribute name="inl"><xs:simpleType><xs:restriction base="xs:int"/></xs:simpleType>
        </xs:attribute>
        <xs:attribute name="plain" default="x" fixed="x"/>
        <xs:attributeGroup ref="tns:Meta"/>
      </xs:complexType></xs:element>
    """
    result = convert_wsdl(one_op(types))
    s = result.document["components"]["schemas"]
    req = s["Req"]
    props = req["properties"]
    assert props["by"] == {"type": "string"}
    assert props["Shared"] == {"type": "string"}
    assert s["Same"] == {"$ref": "#/components/schemas/Base"}
    assert s["Restricted"]["properties"]["id"] == {"type": "string"}
    assert s["Empty"] == {"type": "object"}
    assert s["Measure"]["required"] == ["value", "unit"]
    assert s["EmptySimple"] == {"type": "object"}
    assert s["Node"]["properties"]["child"]["items"] == {"$ref": "#/components/schemas/Node"}
    assert s["Pair"]["properties"]["v"] == {
        "type": "array",
        "items": {"type": "integer", "format": "int32"},
        "minItems": 2,
        "maxItems": 2,
    }
    assert "@id" in props and props["@id"] == {"type": "string"}
    assert props["globalAttr"] == {"type": "string"}
    assert "gone" not in props
    assert props["inl"]["type"] == "integer"
    assert props["plain"] == {"type": "string", "default": "x", "const": "x"}
    assert props["lang"] == {"type": "string"}
    assert req["oneOf"] == [{"required": ["c"]}, {"required": ["d"]}]
    assert "a" in props and "e" in props
    warnings = " ".join(result.warnings)
    for fragment in ("Repeating", "Mixed", "simpleContent", "DoesNotExist"):
        assert fragment in warnings


def test_two_required_choices_use_all_of() -> None:
    types = """<xs:element name="Req"><xs:complexType><xs:sequence>
      <xs:choice><xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:string"/>
      </xs:choice>
      <xs:choice><xs:element name="c" type="xs:string"/><xs:element name="d" type="xs:string"/>
      </xs:choice></xs:sequence></xs:complexType></xs:element>"""
    assert len(schemas_of(one_op(types))["Req"]["allOf"]) == 2


def test_element_with_named_type_is_referenced_directly() -> None:
    types = """<xs:complexType name="T"><xs:sequence><xs:element name="x" type="xs:int"/>
      </xs:sequence></xs:complexType><xs:element name="Req" type="tns:T"/>
      <xs:element name="Nil" type="tns:T" nillable="true"/>"""
    doc = convert_wsdl(one_op(types)).document
    body = doc["paths"]["/do"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    assert body == {"$ref": "#/components/schemas/T"}
    assert doc["paths"]["/do"]["post"]["responses"]["202"]
    nil = convert_wsdl(one_op(types, "tns:Nil")).document["components"]["schemas"]["Nil"]
    assert nil == {"anyOf": [{"$ref": "#/components/schemas/T"}, {"type": "null"}]}


def test_unknown_element_and_message() -> None:
    result = convert_wsdl(one_op("", "tns:Missing"))
    assert any("Missing" in w for w in result.warnings)
    text = wsdl("""<portType name="P"><operation name="Do"><input message="tns:Nope"/>
      <output message="tns:Empty"/></operation></portType><message name="Empty"/>""")
    result = convert_wsdl(text)
    op = result.document["paths"]["/do"]["post"]
    assert "requestBody" not in op and "204" in op["responses"]
    assert any("Nope" in w for w in result.warnings)


def test_multiple_doc_parts_faults_and_one_way() -> None:
    types = """<xs:element name="A" type="xs:string"/><xs:element name="F1" type="xs:string"/>
      <xs:element name="F2" type="xs:string"/>"""
    body = """
      <message name="In"><part name="a" element="tns:A"/><part name="b" type="xs:int"/></message>
      <message name="F1"><part name="f" element="tns:F1"/></message>
      <message name="F2"><part name="f" element="tns:F2"/></message>
      <portType name="P"><operation name="Do"><input message="tns:In"/><output message="tns:In"/>
        <fault name="F1" message="tns:F1"/><fault name="F2" message="tns:F2"/></operation>
        <operation name="Notify"><input message="tns:In"/></operation></portType>"""
    doc = convert_wsdl(wsdl(body, types)).document
    op = doc["paths"]["/do"]["post"]
    assert doc["components"]["schemas"]["DoRequest"]["required"] == ["a", "b"]
    detail = op["responses"]["500"]["content"]["application/json"]["schema"]["allOf"][1]
    assert len(detail["properties"]["detail"]["oneOf"]) == 2
    assert "202" in doc["paths"]["/notify"]["post"]["responses"]


def test_services_bindings_and_collisions() -> None:
    body = """
      <message name="M"/>
      <portType name="One"><operation name="Do"><input message="tns:M"/></operation></portType>
      <portType name="Two"><operation name="Do"><input message="tns:M"/></operation>
        <operation name="do"><input message="tns:M"/></operation></portType>
      <portType name="Three"><operation name="X"><input message="tns:M"/></operation></portType>
      <binding name="B12" type="tns:One"><soap12:binding style="rpc"/>
        <operation name="Do"><soap12:operation soapAction="" style="document"/>
        <input><soap12:body use="literal"/></input></operation></binding>
      <binding name="B11" type="tns:One"><soap:binding/></binding>
      <binding name="BTwo" type="tns:Two"><soap:binding/></binding>
      <binding name="Http" type="tns:Three"><http:binding verb="GET"/></binding>
      <binding name="NoType"/>
      <service name="S">
        <port name="p12" binding="tns:B12"><soap12:address location="https://a.example.com"/></port>
        <port name="p11" binding="tns:B11"><soap:address location="https://a.example.com"/></port>
        <port name="ph" binding="tns:Http"/>
        <port name="pm" binding="tns:Missing"/>
        <port name="pn"/>
      </service>"""
    result = convert_wsdl(wsdl(body))
    paths = result.document["paths"]
    assert set(paths) == {"/do", "/two/do", "/two/do-2", "/x"}
    assert paths["/do"]["post"]["x-soap"]["port"] == "p11"
    ids = sorted(p["post"]["operationId"] for p in paths.values())
    assert ids == ["do", "do2", "do3", "x"]
    assert any("non-SOAP" in w for w in result.warnings)
    assert any("binding not found" in w for w in result.warnings)


def test_soap12_only_and_abstract() -> None:
    body = """<message name="M"/>
      <portType name="One"><operation name="Do"><input message="tns:M"/></operation></portType>
      <binding name="B12" type="tns:One"><soap12:binding style="document"/></binding>"""
    op = convert_wsdl(wsdl(body)).document["paths"]["/do"]["post"]
    assert op["x-soap"] == {
        "operation": "Do",
        "style": "document",
        "binding": "B12",
        "soapVersion": "1.2",
    }


def test_soapenc_array_fallbacks() -> None:
    enc = 'xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/"'
    types = f"""
      <xs:complexType name="ArrSeq" {enc}><xs:complexContent>
        <xs:restriction base="soapenc:Array"><xs:sequence>
          <xs:element name="item" type="xs:int" maxOccurs="unbounded"/></xs:sequence>
        </xs:restriction></xs:complexContent></xs:complexType>
      <xs:complexType name="ArrBare" {enc}><xs:complexContent>
        <xs:restriction base="soapenc:Array"/></xs:complexContent></xs:complexType>
      <xs:element name="Req"><xs:complexType><xs:sequence>
        <xs:element name="a" type="tns:ArrSeq"/><xs:element name="b" type="tns:ArrBare"/>
        <xs:element name="c" type="soapenc:Array" {enc}/>
      </xs:sequence></xs:complexType></xs:element>"""
    s = schemas_of(one_op(types))
    assert s["ArrSeq"]["items"] == {"type": "integer", "format": "int32"}
    assert s["ArrBare"] == {"type": "array"}
    assert s["Req"]["properties"]["c"] == {"type": "array"}


def test_name_collisions_across_namespaces() -> None:
    text = (
        WSDL_HEAD
        + """<types>
      <xs:schema targetNamespace="urn:t"><xs:complexType name="Item"><xs:sequence>
        <xs:element name="other" type="o:Item" xmlns:o="urn:o"/></xs:sequence></xs:complexType>
        <xs:element name="Req"><xs:complexType><xs:sequence><xs:element name="i" type="tns:Item"/>
        </xs:sequence></xs:complexType></xs:element></xs:schema>
      <xs:schema targetNamespace="urn:o"><xs:complexType name="Item"/></xs:schema></types>
      <message name="In"><part name="p" element="tns:Req"/></message>
      <portType name="P"><operation name="Do"><input message="tns:In"/></operation></portType>
      </definitions>"""
    )
    s = convert_wsdl(text).document["components"]["schemas"]
    assert {"Item", "Item2"} <= set(s)


def test_deep_nesting_is_truncated() -> None:
    inner = '<xs:element name="x" type="xs:string"/>'
    for _ in range(70):
        inner = f'<xs:element name="n"><xs:complexType><xs:sequence>{inner}</xs:sequence></xs:complexType></xs:element>'
    types = f'<xs:element name="Req"><xs:complexType><xs:sequence>{inner}</xs:sequence></xs:complexType></xs:element>'
    result = convert_wsdl(one_op(types))
    assert any("deeper than 64" in w for w in result.warnings)


def test_errors() -> None:
    with pytest.raises(InputError, match="definitions"):
        convert_wsdl('<root xmlns="urn:x"/>')
    with pytest.raises(InputError, match="no portType"):
        convert_wsdl(wsdl(""))
    with pytest.raises(InputError, match="Invalid XML"):
        parse_xml("<a><b></a>")


def test_wsdl_import_warns() -> None:
    text = wsdl(
        '<import location="other.wsdl" namespace="urn:o"/>'
        '<message name="M"/><portType name="P"><operation name="Do">'
        '<input message="tns:M"/></operation></portType>'
    )
    assert any("wsdl:import" in w for w in convert_wsdl(text).warnings)


def test_local_xsd_import(tmp_path: Path) -> None:
    (tmp_path / "types.xsd").write_text(
        '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:t">'
        '<xs:include schemaLocation="types.xsd"/>'
        '<xs:element name="Req" type="xs:string"/></xs:schema>'
    )
    text = one_op(
        '<xs:import namespace="urn:t" schemaLocation="types.xsd"/>'
        '<xs:import namespace="urn:t" schemaLocation="missing.xsd"/>'
    )
    result = convert_wsdl(text, base_dir=tmp_path)
    body = result.document["paths"]["/do"]["post"]["requestBody"]
    assert body["content"]["application/json"]["schema"] == {"type": "string"}
    assert any("missing.xsd" in w for w in result.warnings)
    disabled = convert_wsdl(text, base_dir=tmp_path, allow_local_imports=False)
    assert any("imports disabled" in w for w in disabled.warnings)


def test_import_limits(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import speclint.convert.wsdl as wsdl_mod

    (tmp_path / "a.xsd").write_text('<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>')
    (tmp_path / "b.xsd").write_text('<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>')
    text = one_op(
        '<xs:import schemaLocation="a.xsd"/><xs:import schemaLocation="b.xsd"/>', "xs:string"
    )
    monkeypatch.setattr(wsdl_mod, "MAX_IMPORTED_DOCS", 1)
    with pytest.raises(SecurityError, match="More than"):
        convert_wsdl(text, base_dir=tmp_path)
    monkeypatch.setattr(wsdl_mod, "MAX_IMPORTED_DOCS", 50)
    monkeypatch.setattr(wsdl_mod, "DEFAULT_MAX_BYTES", 1)
    with pytest.raises(SecurityError, match="size limit"):
        convert_wsdl(text, base_dir=tmp_path)
