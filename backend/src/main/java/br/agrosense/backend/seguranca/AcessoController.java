package br.agrosense.backend.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

@Controller
public class AcessoController {

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String login(HttpServletRequest request) {
        String aviso = "";
        if (request.getParameterMap().containsKey("erro")) aviso = "E-mail ou senha inválidos.";
        else if (request.getParameterMap().containsKey("limite")) aviso = "Muitas tentativas. Aguarde um minuto e tente novamente.";
        else if (request.getParameterMap().containsKey("saiu")) aviso = "Você saiu da sua conta.";
        else if (request.getParameterMap().containsKey("acesso")) aviso = "Seu acesso mudou. Entre novamente.";
        return pagina("Entrar", """
                <p class="eyebrow">MONITORAMENTO DE MÁQUINAS</p>
                <h1>Entre no AgroSense</h1>
                <p>Acesse as informações da sua equipe.</p>
                <p class="aviso" role="status">%s</p>
                <form action="/login" method="post">
                  %s
                  <label for="email">E-mail</label>
                  <input id="email" name="email" type="email" maxlength="160" autocomplete="username" required autofocus>
                  <label for="senha">Senha</label>
                  <input id="senha" name="password" type="password" maxlength="128" autocomplete="current-password" required>
                  <button class="secundario" type="button" id="mostrar-senha" aria-controls="senha" aria-pressed="false">Mostrar senha</button>
                  <button type="submit">Entrar</button>
                </form>
                """.formatted(esc(aviso), csrf(request)));
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String inicio(@AuthenticationPrincipal UsuarioAcesso usuario, HttpServletRequest request) {
        String admin = "ADMIN".equals(usuario.perfil()) ? "<a href=\"/api/admin/usuarios\">Consultar usuários</a>" : "";
        String perfil = switch (usuario.perfil()) {
            case "OPERADOR" -> "Operador de Máquina";
            case "TECNICO" -> "Técnico de Manutenção";
            case "GESTOR" -> "Gestor";
            default -> "Administrador";
        };
        return pagina("Início", """
                <p class="eyebrow">AGROSENSE</p>
                <h1>Bem-vindo, %s!</h1>
                <p>%s · %s</p>
                <p>Você está conectado. Consulte as leituras recebidas do simulador.</p>
                <nav aria-label="Consultas"><a href="/api/leituras">Leituras recentes</a>%s</nav>
                <form action="/logout" method="post">%s<button type="submit" class="secundario">Sair da conta</button></form>
                """.formatted(perfil, esc(usuario.nome()), esc(usuario.getUsername()), admin, csrf(request)));
    }

    @GetMapping("/api/auth/me")
    @ResponseBody
    public MeuAcesso meuAcesso(@AuthenticationPrincipal UsuarioAcesso u) {
        return new MeuAcesso(u.id(), u.nome(), u.getUsername(), u.perfil());
    }

    @GetMapping("/api/auth/csrf")
    @ResponseBody
    public TokenCsrf token(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new TokenCsrf(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    public record TokenCsrf(String headerName, String parameterName, String token) { }

    public record MeuAcesso(long id, String nome, String email, String perfil) { }

    private static String csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return "<input type=\"hidden\" name=\"" + esc(token.getParameterName()) + "\" value=\"" + esc(token.getToken()) + "\">";
    }

    private static String esc(String valor) { return HtmlUtils.htmlEscape(valor); }

    private static String pagina(String titulo, String conteudo) {
        return """
                <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · AgroSense</title><link rel="stylesheet" href="/assets/auth.css">
                <script src="/assets/auth.js" defer></script></head>
                <body><main>%s</main></body></html>
                """.formatted(esc(titulo), conteudo);
    }
}
