package br.agrosense.backend.usuarios;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UsuarioRepositorio {
    private static final String COLUNAS = "id, nome, email, perfil, ativo, criado_em";
    private static final RowMapper<UsuarioDados> MAPA = (r, n) -> new UsuarioDados(r.getLong("id"),
            r.getString("nome"), r.getString("email"), r.getString("perfil"), r.getBoolean("ativo"),
            r.getTimestamp("criado_em").toInstant());
    private final JdbcTemplate jdbc;
    public UsuarioRepositorio(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void bloquearAlteracoes() { jdbc.execute("SELECT pg_advisory_xact_lock(80321, 3)"); }
    public Optional<UsuarioDados> buscar(long id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM public.usuario WHERE id = ?", MAPA, id).stream().findFirst();
    }
    public List<UsuarioDados> listar(int pagina, int tamanho) {
        return jdbc.query("SELECT " + COLUNAS + " FROM public.usuario ORDER BY id LIMIT ? OFFSET ?", MAPA, tamanho, (long) pagina * tamanho);
    }
    public long total() { return jdbc.queryForObject("SELECT count(*) FROM public.usuario", Long.class); }
    public long administradoresAtivos() {
        return jdbc.queryForObject("SELECT count(*) FROM public.usuario WHERE ativo AND perfil = 'ADMIN'", Long.class);
    }
    public boolean emailEmUso(String email, long ignorar) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM public.usuario WHERE lower(btrim(email)) = ? AND id <> ?)", Boolean.class, email, ignorar));
    }
    public UsuarioDados criar(String nome, String email, String perfil, String hash) {
        return jdbc.queryForObject("INSERT INTO public.usuario(nome,email,perfil,senha_hash,ativo) VALUES (?,?,?,?,TRUE) RETURNING " + COLUNAS,
                MAPA, nome, email, perfil, hash);
    }
    public UsuarioDados atualizar(long id, String nome, String email, String perfil) {
        return jdbc.queryForObject("UPDATE public.usuario SET nome=?, email=?, perfil=? WHERE id=? RETURNING " + COLUNAS,
                MAPA, nome, email, perfil, id);
    }
    public UsuarioDados situacao(long id, boolean ativo) {
        return jdbc.queryForObject("UPDATE public.usuario SET ativo=? WHERE id=? RETURNING " + COLUNAS, MAPA, ativo, id);
    }
    public void senha(long id, String hash) { jdbc.update("UPDATE public.usuario SET senha_hash=? WHERE id=?", hash, id); }
}
