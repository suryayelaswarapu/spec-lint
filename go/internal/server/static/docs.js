// Renders the shared API contract with Swagger UI (kept in a file so CSP needs no inline script).
window.addEventListener("load", () => {
  window.SwaggerUIBundle({ url: "/api/v1/openapi.yaml", dom_id: "#swagger-ui", deepLinking: true });
});
