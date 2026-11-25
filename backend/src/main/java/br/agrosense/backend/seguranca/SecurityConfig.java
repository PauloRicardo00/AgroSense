package br.agrosense.backend.seguranca;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    public TentativasLogin tentativasLogin() { return new TentativasLogin(); }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${agrosense.frontend.origins:}") String origins) {
        List<String> permitidas = Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        for (String origin : permitidas) {
            var uri = java.net.URI.create(origin);
            if (origin.contains("*") || uri.getHost() == null || !List.of("http", "https").contains(uri.getScheme())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty())
                throw new IllegalArgumentException("Configure origens completas do frontend, sem caminho ou curinga");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(permitidas);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-CSRF-TOKEN"));
        config.setExposedHeaders(List.of("Location", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UsuariosAcesso usuarios,
                                                   PasswordEncoder encoder, TentativasLogin tentativas) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(usuarios);
        provider.setPasswordEncoder(encoder);
        http.authenticationProvider(provider).cors(c -> { })
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/login", "/api/auth/csrf", "/assets/auth.css", "/assets/auth.js").permitAll()
                .requestMatchers(HttpMethod.POST, "/login").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/gestao/**").hasAnyRole("GESTOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/ordens-servico", "/api/ordens-servico/*", "/api/ordens-servico/*/historico").hasAnyRole("ADMIN", "GESTOR", "TECNICO", "OPERADOR")
                .requestMatchers(HttpMethod.GET, "/api/setores/*/tecnicos").hasAnyRole("ADMIN", "GESTOR")
                .requestMatchers(HttpMethod.POST, "/api/ordens-servico").hasRole("OPERADOR")
                .requestMatchers(HttpMethod.PATCH, "/api/ordens-servico/*/status").hasAnyRole("GESTOR", "TECNICO")
                .requestMatchers(HttpMethod.PUT, "/api/setores/*/tecnicos/*").hasAnyRole("ADMIN", "GESTOR")
                .requestMatchers(HttpMethod.GET, "/", "/api/auth/me", "/api/leituras", "/api/leituras/historico", "/api/leituras/resumo", "/api/maquinas/*/ultima", "/api/setores", "/api/setores/*", "/api/maquinas", "/api/maquinas/*", "/api/alertas", "/api/alertas/*")
                    .hasAnyRole("OPERADOR", "TECNICO", "GESTOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/setores", "/api/maquinas").hasAnyRole("GESTOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/alertas/*/reconhecimento").hasAnyRole("ADMIN", "GESTOR", "TECNICO")
                .requestMatchers(HttpMethod.PUT, "/api/setores/*", "/api/maquinas/*").hasAnyRole("GESTOR", "ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/setores/*/situacao", "/api/maquinas/*/situacao").hasAnyRole("GESTOR", "ADMIN")
                .anyRequest().denyAll())
            .formLogin(f -> f.loginPage("/login").usernameParameter("email")
                .successHandler((request, response, auth) -> {
                    tentativas.sucesso(auth.getName());
                    request.getSession().setMaxInactiveInterval(-1);
                    if (ApiHttp.json(request)) ApiHttp.escrever(response, 200, Map.of("autenticado", true));
                    else response.sendRedirect("/");
                }).failureHandler((request, response, e) -> {
                    if (ApiHttp.json(request)) ApiHttp.erro(response, 401, "CREDENCIAIS_INVALIDAS", "E-mail ou senha invalidos");
                    else response.sendRedirect("/login?erro");
                }))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler((request, response, auth) -> {
                    if (ApiHttp.json(request)) response.setStatus(204);
                    else response.sendRedirect("/login?saiu");
                })
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID").permitAll())
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                (request, response, ex) -> ApiHttp.erro(response, 401, "LOGIN_NECESSARIO", "Entre para continuar"), ApiHttp::json)
                .accessDeniedHandler((request, response, ex) -> {
                    if (ApiHttp.json(request)) ApiHttp.erro(response, 403,
                            ex instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALIDO" : "SEM_PERMISSAO",
                            ex instanceof org.springframework.security.web.csrf.CsrfException ? "Atualize o token CSRF e tente novamente" : "Voce nao tem permissao para esta operacao");
                    else response.sendError(403);
                }))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")))
            .addFilterAfter(new SessaoAtualFilter(usuarios), LogoutFilter.class)
            .addFilterBefore(new LimiteLoginFilter(tentativas), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
