package br.agrosense.backend.seguranca;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public class LimiteLoginFilter extends OncePerRequestFilter {
    private final TentativasLogin tentativas;
    public LimiteLoginFilter(TentativasLogin tentativas) { this.tentativas = tentativas; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if ("POST".equals(request.getMethod()) && "/login".equals(request.getServletPath())) {
            String email = request.getParameter("email");
            String senha = request.getParameter("password");
            if (email == null || email.length() > 160 || senha == null || senha.length() > 128) {
                if (ApiHttp.json(request)) ApiHttp.erro(response, 401, "CREDENCIAIS_INVALIDAS", "E-mail ou senha invalidos");
                else response.sendRedirect("/login?erro");
                return;
            }
            if (!tentativas.permitir(request.getRemoteAddr(), email)) {
                response.setHeader("Retry-After", "60");
                if (ApiHttp.json(request)) ApiHttp.erro(response, 429, "LIMITE_TENTATIVAS", "Aguarde um minuto antes de tentar novamente");
                else response.sendRedirect("/login?limite");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
