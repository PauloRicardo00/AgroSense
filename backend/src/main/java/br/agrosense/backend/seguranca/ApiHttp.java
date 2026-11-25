package br.agrosense.backend.seguranca;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ApiHttp {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ApiHttp() { }
    public static boolean json(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getServletPath().startsWith("/api/") || (accept != null && accept.contains("application/json"));
    }
    public static void erro(HttpServletResponse response, int status, String codigo, String mensagem) throws IOException {
        escrever(response, status, Map.of("codigo", codigo, "mensagem", mensagem));
    }
    public static void escrever(HttpServletResponse response, int status, Object dados) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.writeValueAsString(dados));
    }
}
