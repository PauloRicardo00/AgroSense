package br.agrosense.backend.usuarios;

import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import br.agrosense.backend.seguranca.UsuariosAcesso;

@Service
public class UsuarioService {
    private static final Set<String> PERFIS = Set.of("ADMIN", "GESTOR", "TECNICO", "OPERADOR");
    private final UsuarioRepositorio repo;
    private final PasswordEncoder encoder;
    public UsuarioService(UsuarioRepositorio repo, PasswordEncoder encoder) { this.repo = repo; this.encoder = encoder; }

    public Pagina listar(int pagina, int tamanho) {
        if (pagina < 0 || tamanho < 1 || tamanho > 100) throw new ErroUsuario(400, "PAGINACAO_INVALIDA", "pagina deve ser >= 0 e tamanho entre 1 e 100");
        return new Pagina(repo.listar(pagina, tamanho), pagina, tamanho, repo.total());
    }
    public UsuarioDados buscar(long id) {
        if (id <= 0) throw new ErroUsuario(400, "ID_INVALIDO", "Informe um identificador positivo");
        return repo.buscar(id).orElseThrow(() -> new ErroUsuario(404, "USUARIO_NAO_ENCONTRADO", "Usuario nao encontrado"));
    }

    @Transactional
    public UsuarioDados criar(long ator, String nome, String email, String perfil, String senha) {
        email = UsuariosAcesso.normalizarEmail(email);
        validarCadastro(nome, email, perfil);
        validarSenha(senha);
        autorizarAlteracao(ator);
        verificarEmail(email, 0);
        return repo.criar(nome.strip(), email, perfil, encoder.encode(senha));
    }

    @Transactional
    public UsuarioDados atualizar(long ator, long id, String nome, String email, String perfil) {
        email = UsuariosAcesso.normalizarEmail(email);
        validarCadastro(nome, email, perfil);
        autorizarAlteracao(ator);
        UsuarioDados atual = buscar(id);
        protegerAdministrador(atual, perfil, atual.ativo());
        verificarEmail(email, id);
        return repo.atualizar(id, nome.strip(), email, perfil);
    }

    @Transactional
    public UsuarioDados situacao(long ator, long id, Boolean ativo) {
        if (ativo == null) throw new ErroUsuario(400, "DADOS_INVALIDOS", "Informe ativo como true ou false");
        autorizarAlteracao(ator);
        UsuarioDados atual = buscar(id);
        protegerAdministrador(atual, atual.perfil(), ativo);
        return repo.situacao(id, ativo);
    }

    @Transactional
    public void senha(long ator, long id, String senha) {
        validarSenha(senha);
        autorizarAlteracao(ator);
        buscar(id);
        repo.senha(id, encoder.encode(senha));
    }

    private void autorizarAlteracao(long ator) {
        repo.bloquearAlteracoes();
        UsuarioDados u = repo.buscar(ator).orElseThrow(() -> new ErroUsuario(403, "SEM_PERMISSAO", "Acesso de administrador necessario"));
        if (!u.ativo() || !"ADMIN".equals(u.perfil())) throw new ErroUsuario(403, "SEM_PERMISSAO", "Acesso de administrador necessario");
    }
    private void protegerAdministrador(UsuarioDados atual, String perfil, boolean ativo) {
        if (atual.ativo() && "ADMIN".equals(atual.perfil()) && (!ativo || !"ADMIN".equals(perfil)) && repo.administradoresAtivos() <= 1)
            throw new ErroUsuario(409, "ULTIMO_ADMIN", "Nao e permitido desativar ou remover o perfil do ultimo administrador ativo");
    }
    private void verificarEmail(String email, long ignorar) {
        if (repo.emailEmUso(email, ignorar)) throw new ErroUsuario(409, "EMAIL_EM_USO", "Este e-mail ja esta cadastrado");
    }
    private static void validarCadastro(String nome, String email, String perfil) {
        if (nome == null || nome.isBlank() || nome.strip().length() > 120 || nome.chars().anyMatch(Character::isISOControl))
            throw new ErroUsuario(400, "DADOS_INVALIDOS", "Informe um nome de ate 120 caracteres");
        if (email == null || email.length() > 160 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new ErroUsuario(400, "DADOS_INVALIDOS", "Informe um e-mail valido de ate 160 caracteres");
        if (perfil == null || !PERFIS.contains(perfil)) throw new ErroUsuario(400, "PERFIL_INVALIDO", "Perfil deve ser ADMIN, GESTOR, TECNICO ou OPERADOR");
    }
    private static void validarSenha(String senha) {
        if (senha == null || senha.isBlank() || senha.length() < 15 || senha.length() > 128)
            throw new ErroUsuario(400, "SENHA_INVALIDA", "A senha deve ter entre 15 e 128 caracteres");
    }
    public record Pagina(List<UsuarioDados> itens, int pagina, int tamanho, long total) { }
}
