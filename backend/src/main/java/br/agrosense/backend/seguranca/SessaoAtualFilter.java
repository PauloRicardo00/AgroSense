package br.agrosense.backend.seguranca;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessaoAtualFilter extends OncePerRequestFilter {
    private final UsuariosAcesso usuarios;
    public SessaoAtualFilter(UsuariosAcesso usuarios) { this.usuarios = usuarios; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UsuarioAcesso usuario) {
            try {
                if (!usuarios.sessaoValida(usuario)) {
                    if (request.getSession(false) != null) request.getSession(false).invalidate();
                    SecurityContextHolder.clearContext();
                    if (ApiHttp.json(request)) ApiHttp.erro(response, 401, "SESSAO_ENCERRADA", "Seu acesso mudou; entre novamente");
                    else response.sendRedirect("/login?acesso");
                    return;
                }
            } catch (DataAccessException e) {
                response.setStatus(503);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Acesso temporariamente indisponível. Tente novamente.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
