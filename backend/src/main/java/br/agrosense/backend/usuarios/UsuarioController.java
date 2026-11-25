package br.agrosense.backend.usuarios;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import br.agrosense.backend.seguranca.UsuarioAcesso;

@RestController
@RequestMapping("/api/admin/usuarios")
@PreAuthorize("hasRole('ADMIN')")
public class UsuarioController {
    private final UsuarioService service;
    public UsuarioController(UsuarioService service) { this.service = service; }
    @GetMapping
    public UsuarioService.Pagina listar(@RequestParam(defaultValue="0") int pagina, @RequestParam(defaultValue="20") int tamanho) {
        return service.listar(pagina, tamanho);
    }
    @GetMapping("/{id}")
    public UsuarioDados buscar(@PathVariable long id) { return service.buscar(id); }
    @PostMapping
    public ResponseEntity<UsuarioDados> criar(@AuthenticationPrincipal UsuarioAcesso ator, @RequestBody NovoUsuario dados) {
        UsuarioDados u = service.criar(ator.id(), dados.nome(), dados.email(), dados.perfil(), dados.senha());
        return ResponseEntity.created(URI.create("/api/admin/usuarios/" + u.id())).body(u);
    }
    @PutMapping("/{id}")
    public UsuarioDados atualizar(@AuthenticationPrincipal UsuarioAcesso ator, @PathVariable long id, @RequestBody EditarUsuario dados) {
        return service.atualizar(ator.id(), id, dados.nome(), dados.email(), dados.perfil());
    }
    @PatchMapping("/{id}/situacao")
    public UsuarioDados situacao(@AuthenticationPrincipal UsuarioAcesso ator, @PathVariable long id, @RequestBody Situacao dados) {
        return service.situacao(ator.id(), id, dados.ativo());
    }
    @PutMapping("/{id}/senha")
    public ResponseEntity<Void> senha(@AuthenticationPrincipal UsuarioAcesso ator, @PathVariable long id, @RequestBody NovaSenha dados) {
        service.senha(ator.id(), id, dados.senha());
        return ResponseEntity.noContent().build();
    }
    public record NovoUsuario(String nome, String email, String perfil, String senha) {
        @Override public String toString() { return "NovoUsuario[dados protegidos]"; }
    }
    public record EditarUsuario(String nome, String email, String perfil) { }
    public record Situacao(Boolean ativo) { }
    public record NovaSenha(String senha) {
        @Override public String toString() { return "NovaSenha[dados protegidos]"; }
    }
}
