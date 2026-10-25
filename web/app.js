// spec-lint web UI. Plain JavaScript, no dependencies; all output is inserted with
// textContent (never innerHTML) so contract content cannot inject markup.
"use strict";

const MAX_BYTES = 2 * 1024 * 1024;
const $ = (id) => document.getElementById(id);

const SAMPLES = {
  wsdl: { filename: "weather.wsdl", text: `<?xml version="1.0" encoding="UTF-8"?>
<definitions name="WeatherService" targetNamespace="http://example.com/weather"
    xmlns="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="http://example.com/weather">
  <types>
    <xs:schema targetNamespace="http://example.com/weather">
      <xs:simpleType name="Units">
        <xs:restriction base="xs:string">
          <xs:enumeration value="metric"/><xs:enumeration value="imperial"/>
        </xs:restriction>
      </xs:simpleType>
      <xs:element name="GetForecast">
        <xs:complexType><xs:sequence>
          <xs:element name="city" type="xs:string"/>
          <xs:element name="days" type="xs:int" minOccurs="0" default="3"/>
          <xs:element name="units" type="tns:Units" minOccurs="0"/>
        </xs:sequence></xs:complexType>
      </xs:element>
      <xs:element name="GetForecastResponse">
        <xs:complexType><xs:sequence>
          <xs:element name="day" maxOccurs="unbounded">
            <xs:complexType><xs:sequence>
              <xs:element name="date" type="xs:date"/>
              <xs:element name="high" type="xs:double"/>
              <xs:element name="low" type="xs:double"/>
            </xs:sequence></xs:complexType>
          </xs:element>
        </xs:sequence></xs:complexType>
      </xs:element>
    </xs:schema>
  </types>
  <message name="GetForecastIn"><part name="parameters" element="tns:GetForecast"/></message>
  <message name="GetForecastOut"><part name="parameters" element="tns:GetForecastResponse"/></message>
  <portType name="WeatherPort">
    <operation name="GetForecast">
      <input message="tns:GetForecastIn"/><output message="tns:GetForecastOut"/>
    </operation>
  </portType>
  <binding name="WeatherSoap" type="tns:WeatherPort">
    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
    <operation name="GetForecast">
      <soap:operation soapAction="http://example.com/weather/GetForecast"/>
      <input><soap:body use="literal"/></input><output><soap:body use="literal"/></output>
    </operation>
  </binding>
  <service name="WeatherService">
    <port name="WeatherSoap" binding="tns:WeatherSoap">
      <soap:address location="https://soap.example.com/weather"/>
    </port>
  </service>
</definitions>
` },
  swagger: { filename: "weather-v2.yaml", text: `swagger: "2.0"
info:
  title: Weather API
  version: "2.3"
host: api.example.com
basePath: /v2
schemes: [https]
paths:
  /Current_Conditions/{city}:
    get:
      summary: Current conditions for a city
      parameters:
        - {name: city, in: path, required: true, type: string}
      responses:
        "200":
          description: OK
          schema:
            type: object
            properties:
              temperature_c: {type: number}
              summary: {type: string, x-nullable: true}
  /forecast/:
    get:
      operationId: GetForecast
      parameters:
        - {name: lat, in: query, required: true, type: number}
        - {name: lon, in: query, required: true, type: number}
      responses:
        "200":
          description: OK
` },
  openapi: { filename: "alerts.yaml", text: `openapi: 3.0.3
info:
  title: Weather Alerts API
  version: 1.4.0
servers:
  - url: https://alerts.example.com/v1
paths:
  /alerts/{alertId}:
    get:
      operationId: getAlert
      summary: Get one alert
      parameters:
        - {name: alertId, in: path, required: true, schema: {type: string}}
      responses:
        "200":
          description: The alert
          content:
            application/json:
              schema:
                type: object
                properties:
                  headline: {type: string, nullable: true}
` },
};

const state = { filename: "spec.yaml", yaml: "" };

function token() {
  return $("token").value.trim();
}

function rememberToken() {
  try { sessionStorage.setItem("speclint-token", token()); } catch (_) { /* storage unavailable */ }
}

function restoreToken() {
  try { $("token").value = sessionStorage.getItem("speclint-token") || ""; } catch (_) { /* ignore */ }
}

async function call(path, body) {
  const headers = { "Content-Type": "application/json", Accept: "application/json" };
  if (token()) headers.Authorization = `Bearer ${token()}`;
  const res = await fetch(path, { method: "POST", headers, body: JSON.stringify(body) });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.detail || data.title || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function cell(row, text, className) {
  const td = document.createElement("td");
  if (className) {
    const span = document.createElement("span");
    span.className = className;
    span.textContent = text;
    td.appendChild(span);
  } else {
    td.textContent = text;
  }
  row.appendChild(td);
  return td;
}

function fill(tbodyId, rows, render, emptyText) {
  const body = $(tbodyId);
  body.replaceChildren();
  if (!rows.length) {
    const tr = document.createElement("tr");
    const td = cell(tr, emptyText);
    td.colSpan = 4;
    body.appendChild(tr);
    return;
  }
  rows.forEach((r) => { const tr = document.createElement("tr"); render(tr, r); body.appendChild(tr); });
}

function selectTab(name) {
  document.querySelectorAll(".tabs button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === name)));
  document.querySelectorAll(".tab").forEach((p) => { p.hidden = p.dataset.pane !== name; });
}

function show(result, action) {
  $("error").hidden = true;
  $("empty").hidden = true;
  $("result").hidden = false;
  const summary = result.summary || { error: 0, warning: 0, info: 0 };
  $("n-error").textContent = summary.error;
  $("n-warning").textContent = summary.warning;
  $("n-info").textContent = summary.info;
  $("n-fixes").textContent = (result.fixes || []).length;
  $("n-notes").textContent = (result.warnings || []).length;
  $("kind").textContent = result.sourceKind ? `Detected: ${result.sourceKind}` : "";

  state.yaml = result.yaml || "";
  $("yaml").textContent = state.yaml || "(lint does not change the document)";
  fill("findings", result.findings || [], (tr, f) => {
    cell(tr, f.severity, `sev ${f.severity}`);
    cell(tr, f.rule);
    cell(tr, f.line ? String(f.line) : "");
    cell(tr, f.message);
  }, action === "convert" ? "Convert does not lint; use Modernize." : "No findings. The contract meets the standard.");
  fill("fixes", result.fixes || [], (tr, f) => { cell(tr, f.rule); cell(tr, f.message); }, "No fixes applied.");
  const notes = $("notes");
  notes.replaceChildren();
  (result.warnings || []).forEach((w) => { const li = document.createElement("li"); li.textContent = w; notes.appendChild(li); });
  if (!notes.children.length) { const li = document.createElement("li"); li.textContent = "None."; notes.appendChild(li); }
  $("report").textContent = result.report || "Reports are produced by Modernize.";
  selectTab(action === "lint" ? "findings" : "openapi");
  if (window.matchMedia("(max-width: 960px)").matches) $("result-title").scrollIntoView({ behavior: "smooth" });
}

function showError(message) {
  $("empty").hidden = true;
  $("result").hidden = true;
  $("error").hidden = false;
  $("error").textContent = message;
}

async function run(action) {
  const spec = $("spec").value;
  if (!spec.trim()) { showError("Paste a contract or pick a sample first."); return; }
  rememberToken();
  const body = { spec, filename: state.filename, config: { extends: $("profile").value } };
  if (action === "modernize") body.fix = $("fix").checked;
  if (action === "convert") delete body.config;
  const buttons = document.querySelectorAll(".actions button");
  buttons.forEach((b) => { b.disabled = true; });
  try {
    show(await call(`/api/v1/${action}`, body), action);
  } catch (err) {
    showError(err.status === 401 ? "This server requires an API token (see 'API token')." : err.message);
  } finally {
    buttons.forEach((b) => { b.disabled = false; });
  }
}

function loadFile(file) {
  if (!file) return;
  if (file.size > MAX_BYTES) { showError(`${file.name} is larger than 2 MB.`); return; }
  const reader = new FileReader();
  reader.onload = () => { $("spec").value = reader.result; state.filename = file.name; };
  reader.readAsText(file);
}

function download() {
  if (!state.yaml) return;
  const blob = new Blob([state.yaml], { type: "application/yaml" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = state.filename.replace(/\.[^.]+$/, "") + ".openapi.yaml";
  a.click();
  URL.revokeObjectURL(a.href);
}

async function init() {
  restoreToken();
  try {
    const health = await (await fetch("/health")).json();
    $("impl").textContent = `${health.implementation} · v${health.version}`;
  } catch (_) {
    $("impl").textContent = "offline";
  }
  document.querySelectorAll("[data-sample]").forEach((b) => b.addEventListener("click", () => {
    const s = SAMPLES[b.dataset.sample];
    $("spec").value = s.text;
    state.filename = s.filename;
  }));
  document.querySelectorAll("[data-action]").forEach((b) => b.addEventListener("click", () => run(b.dataset.action)));
  document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => selectTab(b.dataset.tab)));
  $("file").addEventListener("change", (e) => loadFile(e.target.files[0]));
  const drop = $("drop");
  drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
  drop.addEventListener("dragleave", () => drop.classList.remove("over"));
  drop.addEventListener("drop", (e) => { e.preventDefault(); drop.classList.remove("over"); loadFile(e.dataTransfer.files[0]); });
  $("copy").addEventListener("click", () => navigator.clipboard && navigator.clipboard.writeText(state.yaml));
  $("download").addEventListener("click", download);
}

document.addEventListener("DOMContentLoaded", init);
