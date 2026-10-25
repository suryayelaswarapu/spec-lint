package io.github.suryayelaswarapu.speclint;

import static io.github.suryayelaswarapu.speclint.ConvertTest.at;
import static io.github.suryayelaswarapu.speclint.Json.map;
import static io.github.suryayelaswarapu.speclint.TestSupport.normalize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.SpecLintException.SecurityException;
import io.github.suryayelaswarapu.speclint.convert.ConversionResult;
import io.github.suryayelaswarapu.speclint.convert.WsdlConverter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WsdlTest {
    static final String HEAD = """
            <?xml version="1.0"?>
            <definitions xmlns="http://schemas.xmlsoap.org/wsdl/"
              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
              xmlns:soap12="http://schemas.xmlsoap.org/wsdl/soap12/"
              xmlns:http="http://schemas.xmlsoap.org/wsdl/http/"
              xmlns:xs="http://www.w3.org/2001/XMLSchema"
              xmlns:tns="urn:t" targetNamespace="urn:t" name="T">
            """;

    @TempDir
    Path tmp;

    static String wsdl(String body, String types) {
        return HEAD + "<types><xs:schema targetNamespace=\"urn:t\">" + types
                + "</xs:schema></types>" + body + "</definitions>";
    }

    static String oneOp(String types, String inputEl) {
        return wsdl("<message name=\"In\"><part name=\"p\" element=\"" + inputEl + "\"/></message>"
                + "<portType name=\"P\"><operation name=\"Do\"><input message=\"tns:In\"/>"
                + "</operation></portType>", types);
    }

    static ConversionResult convert(String text) {
        return new WsdlConverter(null, true).convert(text);
    }

    static Map<String, Object> schemas(String text) {
        return map(at(convert(text).document(), "components", "schemas"));
    }

    @Test
    void builtinFacetsAndLists() {
        String types = """
                <xs:simpleType name="Code"><xs:restriction base="xs:string">
                  <xs:length value="3"/><xs:pattern value="[A-Z]+"/><xs:pattern value="[0-9]+"/>
                </xs:restriction></xs:simpleType>
                <xs:simpleType name="Temp"><xs:restriction base="xs:decimal">
                  <xs:minExclusive value="-1.5"/><xs:maxExclusive value="60"/>
                  <xs:minLength value="1"/><xs:maxLength value="9"/>
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
                <xs:simpleType name="Empty"/>
                <xs:simpleType name="Bad"><xs:restriction base="xs:int"><xs:enumeration value="x"/>
                </xs:restriction></xs:simpleType>
                <xs:simpleType name="Flag"><xs:restriction base="xs:boolean"/></xs:simpleType>
                <xs:simpleType name="Wrapped"><xs:restriction><xs:simpleType>
                  <xs:restriction base="xs:int"/></xs:simpleType><xs:maxInclusive value="5"/>
                </xs:restriction></xs:simpleType>
                <xs:element name="Req"><xs:complexType><xs:sequence>
                  <xs:element name="code" type="tns:Code"/>
                  <xs:element name="temp" type="tns:Temp"/>
                  <xs:element name="level" type="tns:Level"/>
                  <xs:element name="child" type="tns:Child"/>
                  <xs:element name="codes" type="tns:Codes"/>
                  <xs:element name="inline" type="tns:Inline"/>
                  <xs:element name="either" type="tns:Either"/>
                  <xs:element name="nothing" type="tns:Nothing"/>
                  <xs:element name="empty" type="tns:Empty"/>
                  <xs:element name="bad" type="tns:Bad"/>
                  <xs:element name="wrapped" type="tns:Wrapped"/>
                  <xs:element name="flag" type="tns:Flag" default="true"/>
                  <xs:element name="fixed" type="xs:int" fixed="7"/>
                  <xs:element name="ratio" type="xs:float" default="0.5"/>
                  <xs:element name="big" type="xs:integer" default="99999999999999999999"/>
                  <xs:element name="word" type="xs:int" default="many"/>
                  <xs:element name="any"/>
                  <xs:element name="raw" type="xs:base64Binary"/>
                  <xs:element name="encoded" type="soapenc:string"
                      xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/"/>
                </xs:sequence></xs:complexType></xs:element>
                """;
        Map<String, Object> s = schemas(oneOp(types, "tns:Req"));
        assertThat(normalize(s.get("Code"))).isEqualTo(normalize(Json.obj("type", "string",
                "minLength", 3, "maxLength", 3, "pattern", "^(?:[A-Z]+|[0-9]+)$")));
        assertThat(normalize(s.get("Temp"))).isEqualTo(normalize(Json.obj("type", "number",
                "exclusiveMinimum", -1.5, "exclusiveMaximum", 60, "minLength", 1,
                "maxLength", 9)));
        assertThat(normalize(at(s, "Level", "enum"))).isEqualTo(normalize(List.of(1, 2)));
        assertThat(map(s.get("Child"))).containsEntry("$ref", "#/components/schemas/Level");
        assertThat(map(at(s, "Inline", "items"))).containsEntry("format", "int32");
        assertThat(Json.list(at(s, "Either", "anyOf"))).hasSize(3);
        assertThat(map(s.get("Nothing"))).isEmpty();
        assertThat(map(s.get("Empty"))).isEmpty();
        assertThat(at(s, "Bad", "enum")).isEqualTo(List.of("x"));
        assertThat(normalize(at(s, "Wrapped", "maximum"))).isEqualTo(normalize(5));
        Map<String, Object> props = map(at(s, "Req", "properties"));
        assertThat(at(props, "flag", "default")).isEqualTo(true);
        assertThat(normalize(at(props, "fixed", "const"))).isEqualTo(normalize(7));
        assertThat(normalize(at(props, "ratio", "default"))).isEqualTo(normalize(0.5));
        assertThat(at(props, "big", "default").toString()).isEqualTo("99999999999999999999");
        assertThat(at(props, "word", "default")).isEqualTo("many");
        assertThat(map(props.get("any"))).isEmpty();
        assertThat(map(props.get("encoded"))).containsEntry("type", "string");
    }

    @Test
    void complexFeatures() {
        String types = """
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
                <xs:complexType name="Restr"><xs:simpleContent><xs:restriction base="xs:double"/>
                </xs:simpleContent></xs:complexType>
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
                    <xs:element name="restr" type="tns:Restr"/>
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
                  <xs:attributeGroup ref="tns:NoGroup"/>
                </xs:complexType></xs:element>
                """;
        ConversionResult r = convert(oneOp(types, "tns:Req"));
        Map<String, Object> s = map(at(r.document(), "components", "schemas"));
        Map<String, Object> props = map(at(s, "Req", "properties"));
        assertThat(map(props.get("by"))).containsEntry("type", "string");
        assertThat(map(props.get("Shared"))).containsEntry("type", "string");
        assertThat(map(s.get("Same"))).containsEntry("$ref", "#/components/schemas/Base");
        assertThat(map(s.get("Empty"))).isEqualTo(Json.obj("type", "object"));
        assertThat(at(s, "Measure", "required")).isEqualTo(List.of("value", "unit"));
        assertThat(map(s.get("EmptySimple"))).isEqualTo(Json.obj("type", "object"));
        assertThat(normalize(at(s, "Pair", "properties", "v", "minItems")))
                .isEqualTo(normalize(2));
        assertThat(props).containsKeys("@id", "globalAttr", "inl", "plain", "lang", "a", "e")
                .doesNotContainKey("gone");
        assertThat(map(props.get("plain"))).containsEntry("const", "x");
        assertThat(at(s, "Req", "oneOf")).isEqualTo(List.of(Json.obj("required", List.of("c")),
                Json.obj("required", List.of("d"))));
        String warnings = String.join(" ", r.warnings());
        for (String fragment : List.of("Repeating", "Mixed", "simpleContent", "DoesNotExist")) {
            assertThat(warnings).contains(fragment);
        }
    }

    @Test
    void twoChoicesUseAllOf() {
        String types = """
                <xs:element name="Req"><xs:complexType><xs:sequence>
                <xs:choice><xs:element name="a" type="xs:string"/><xs:element name="b" type="xs:string"/>
                </xs:choice>
                <xs:choice><xs:element name="c" type="xs:string"/><xs:element name="d" type="xs:string"/>
                </xs:choice></xs:sequence></xs:complexType></xs:element>""";
        assertThat(Json.list(at(schemas(oneOp(types, "tns:Req")), "Req", "allOf"))).hasSize(2);
    }

    @Test
    void namedTypesNillableAndUnknowns() {
        String types = """
                <xs:complexType name="T"><xs:sequence><xs:element name="x" type="xs:int"/>
                </xs:sequence></xs:complexType><xs:element name="Req" type="tns:T"/>
                <xs:element name="Nil" type="tns:T" nillable="true"/>""";
        Map<String, Object> doc = convert(oneOp(types, "tns:Req")).document();
        assertThat(at(doc, "paths", "/do", "post", "requestBody", "content", "application/json",
                "schema")).isEqualTo(Json.obj("$ref", "#/components/schemas/T"));
        assertThat(map(at(doc, "paths", "/do", "post", "responses"))).containsKey("202");
        assertThat(map(schemas(oneOp(types, "tns:Nil")).get("Nil"))).containsKey("anyOf");
        assertThat(convert(oneOp("", "tns:Missing")).warnings())
                .anyMatch(w -> w.contains("Missing"));
        ConversionResult r = convert(wsdl("""
                <portType name="P"><operation name="Do"><input message="tns:Nope"/>
                <output message="tns:Empty"/></operation></portType><message name="Empty"/>""", ""));
        assertThat(map(at(r.document(), "paths", "/do", "post", "responses"))).containsKey("204");
        assertThat(r.warnings()).anyMatch(w -> w.contains("Nope"));
    }

    @Test
    void multiplePartsFaultsAndOneWay() {
        String types = """
                <xs:element name="A" type="xs:string"/><xs:element name="F1" type="xs:string"/>
                <xs:element name="F2" type="xs:string"/>""";
        String body = """
                <message name="In"><part name="a" element="tns:A"/><part name="b" type="xs:int"/></message>
                <message name="F1"><part name="f" element="tns:F1"/></message>
                <message name="F2"><part name="f" element="tns:F2"/></message>
                <portType name="P"><operation name="Do"><input message="tns:In"/><output message="tns:In"/>
                  <fault name="F1" message="tns:F1"/><fault name="F2" message="tns:F2"/></operation>
                  <operation name="Notify"><input message="tns:In"/></operation></portType>""";
        Map<String, Object> doc = convert(wsdl(body, types)).document();
        assertThat(at(doc, "components", "schemas", "DoRequest", "required"))
                .isEqualTo(List.of("a", "b"));
        assertThat(Json.list(at(doc, "paths", "/do", "post", "responses", "500", "content",
                "application/json", "schema", "allOf", "1", "properties", "detail", "oneOf")))
                .hasSize(2);
    }

    @Test
    void servicesBindingsAndCollisions() {
        String body = """
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
                </service>""";
        ConversionResult r = convert(wsdl(body, ""));
        Map<String, Object> paths = map(r.document().get("paths"));
        assertThat(paths.keySet()).containsExactlyInAnyOrder("/do", "/two/do", "/two/do-2", "/x");
        assertThat(at(paths, "/do", "post", "x-soap", "port")).isEqualTo("p11");
        assertThat(r.warnings()).anyMatch(w -> w.contains("non-SOAP"))
                .anyMatch(w -> w.contains("binding not found"));
    }

    @Test
    void soap12OnlyAndArrays() {
        String body = """
                <message name="M"/>
                <portType name="One"><operation name="Do"><input message="tns:M"/></operation></portType>
                <binding name="B12" type="tns:One"><soap12:binding style="document"/></binding>""";
        assertThat(map(at(convert(wsdl(body, "")).document(), "paths", "/do", "post", "x-soap")))
                .containsEntry("soapVersion", "1.2");
        String enc = "xmlns:soapenc=\"http://schemas.xmlsoap.org/soap/encoding/\"";
        String types = "<xs:complexType name=\"ArrSeq\" " + enc + "><xs:complexContent>"
                + "<xs:restriction base=\"soapenc:Array\"><xs:sequence>"
                + "<xs:element name=\"item\" type=\"xs:int\" maxOccurs=\"unbounded\"/></xs:sequence>"
                + "</xs:restriction></xs:complexContent></xs:complexType>"
                + "<xs:complexType name=\"ArrBare\" " + enc + "><xs:complexContent>"
                + "<xs:restriction base=\"soapenc:Array\"/></xs:complexContent></xs:complexType>"
                + "<xs:element name=\"Req\"><xs:complexType><xs:sequence>"
                + "<xs:element name=\"a\" type=\"tns:ArrSeq\"/><xs:element name=\"b\" type=\"tns:ArrBare\"/>"
                + "<xs:element name=\"c\" type=\"soapenc:Array\" " + enc + "/>"
                + "</xs:sequence></xs:complexType></xs:element>";
        Map<String, Object> s = schemas(oneOp(types, "tns:Req"));
        assertThat(map(at(s, "ArrSeq", "items"))).containsEntry("format", "int32");
        assertThat(map(s.get("ArrBare"))).isEqualTo(Json.obj("type", "array"));
    }

    @Test
    void collisionsDeepNestingAndErrors() {
        String text = HEAD + """
                <types>
                <xs:schema targetNamespace="urn:t"><xs:complexType name="Item"><xs:sequence>
                  <xs:element name="other" type="o:Item" xmlns:o="urn:o"/></xs:sequence></xs:complexType>
                  <xs:element name="Req"><xs:complexType><xs:sequence><xs:element name="i" type="tns:Item"/>
                  </xs:sequence></xs:complexType></xs:element></xs:schema>
                <xs:schema targetNamespace="urn:o"><xs:complexType name="Item"/></xs:schema></types>
                <message name="In"><part name="p" element="tns:Req"/></message>
                <portType name="P"><operation name="Do"><input message="tns:In"/></operation></portType>
                </definitions>""";
        assertThat(schemas(text)).containsKeys("Item", "Item2");

        String inner = "<xs:element name=\"x\" type=\"xs:string\"/>";
        for (int i = 0; i < 70; i++) {
            inner = "<xs:element name=\"n\"><xs:complexType><xs:sequence>" + inner
                    + "</xs:sequence></xs:complexType></xs:element>";
        }
        String deep = "<xs:element name=\"Req\"><xs:complexType><xs:sequence>" + inner
                + "</xs:sequence></xs:complexType></xs:element>";
        assertThat(convert(oneOp(deep, "tns:Req")).warnings())
                .anyMatch(w -> w.contains("deeper than 64"));

        assertThatThrownBy(() -> convert("<root xmlns=\"urn:x\"/>"))
                .isInstanceOf(InputException.class).hasMessageContaining("definitions");
        assertThatThrownBy(() -> convert(wsdl("", "")))
                .isInstanceOf(InputException.class).hasMessageContaining("no portType");
        String imp = wsdl("<import location=\"other.wsdl\" namespace=\"urn:o\"/><message name=\"M\"/>"
                + "<portType name=\"P\"><operation name=\"Do\"><input message=\"tns:M\"/>"
                + "</operation></portType>", "");
        assertThat(convert(imp).warnings()).anyMatch(w -> w.contains("wsdl:import"));
    }

    @Test
    void localImportsAndLimits() throws Exception {
        Files.writeString(tmp.resolve("types.xsd"),
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:t\">"
                        + "<xs:include schemaLocation=\"types.xsd\"/>"
                        + "<xs:element name=\"Req\" type=\"xs:string\"/></xs:schema>");
        String text = oneOp("<xs:import namespace=\"urn:t\" schemaLocation=\"types.xsd\"/>"
                + "<xs:import namespace=\"urn:t\" schemaLocation=\"missing.xsd\"/>", "tns:Req");
        ConversionResult r = new WsdlConverter(tmp, true).convert(text);
        assertThat(at(r.document(), "paths", "/do", "post", "requestBody", "content",
                "application/json", "schema")).isEqualTo(Json.obj("type", "string"));
        assertThat(r.warnings()).anyMatch(w -> w.contains("missing.xsd"));
        assertThat(new WsdlConverter(tmp, false).convert(text).warnings())
                .anyMatch(w -> w.contains("imports disabled"));

        Files.writeString(tmp.resolve("a.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        Files.writeString(tmp.resolve("b.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        String two = oneOp("<xs:import schemaLocation=\"a.xsd\"/><xs:import schemaLocation=\"b.xsd\"/>",
                "xs:string");
        assertThatThrownBy(() -> new WsdlConverter(tmp, true, 1, 1024).convert(two))
                .isInstanceOf(SecurityException.class).hasMessageContaining("More than");
        assertThatThrownBy(() -> new WsdlConverter(tmp, true, 50, 1L).convert(two))
                .isInstanceOf(SecurityException.class).hasMessageContaining("size limit");
    }

    @Test
    void weatherFixtures() throws IOException {
        ConversionResult r = io.github.suryayelaswarapu.speclint.convert.Converters.convertSource(
                io.github.suryayelaswarapu.speclint.io.SpecIO.readSource(
                        TestSupport.testdata("weather/weather-service.wsdl")));
        assertThat(at(r.document(), "paths", "/get-forecast", "post", "x-soap", "soapVersion"))
                .isEqualTo("1.1");
    }
}
