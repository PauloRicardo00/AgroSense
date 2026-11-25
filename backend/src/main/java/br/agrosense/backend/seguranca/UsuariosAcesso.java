package br.agrosense.backend.seguranca;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UsuariosAcesso implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public UsuariosAcesso(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String normalizarEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        List<UsuarioAcesso> usuarios = jdbc.query("""
                SELECT id, nome, email, senha_hash, perfil, ativo FROM public.usuario
                WHERE lower(btrim(email)) = ?
                """, (r, n) -> new UsuarioAcesso(r.getLong("id"), r.getString("nome"), r.getString("email"),
                r.getString("senha_hash"), r.getString("perfil"), r.getBoolean("ativo")), normalizarEmail(email));
        if (usuarios.size() != 1) throw new UsernameNotFoundException("Credenciais invalidas");
        return usuarios.getFirst();
    }

    public boolean sessaoValida(UsuarioAcesso usuario) {
        List<String> assinaturas = jdbc.query("""
                SELECT id, email, senha_hash, perfil, ativo FROM public.usuario WHERE id = ? AND ativo
                """, (r, n) -> UsuarioAcesso.assinatura(r.getLong("id"), r.getString("email"),
                r.getString("senha_hash"), r.getString("perfil"), r.getBoolean("ativo")), usuario.id());
        return assinaturas.size() == 1 && assinaturas.getFirst().equals(usuario.assinatura());
    }

}
