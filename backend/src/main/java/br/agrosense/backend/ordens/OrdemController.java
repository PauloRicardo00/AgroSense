package br.agrosense.backend.ordens;

import static br.agrosense.backend.ordens.OrdemDados.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import br.agrosense.backend.seguranca.UsuarioAcesso;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','GESTOR','TECNICO','OPERADOR')")
public class OrdemController {
    private final OrdemService service;
    public OrdemController(OrdemService service) { this.service=service; }
    @GetMapping("/api/ordens-servico")
    public Pagina<Ordem> listar(@AuthenticationPrincipal UsuarioAcesso usuario,
        @RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamanho,
        @RequestParam(required=false) String status,@RequestParam(required=false) String maquinaId) {
        return service.listar(usuario.id(),pagina,tamanho,status,maquinaId);
    }
    @GetMapping("/api/ordens-servico/{id}")
    public Ordem buscar(@PathVariable long id,@AuthenticationPrincipal UsuarioAcesso usuario) { return service.buscar(id,usuario.id()); }
    @GetMapping("/api/ordens-servico/{id}/historico")
    public Pagina<Historico> historico(@PathVariable long id,@AuthenticationPrincipal UsuarioAcesso usuario,
        @RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamanho) {
        return service.historico(id,usuario.id(),pagina,tamanho);
    }
    @PostMapping("/api/ordens-servico")
    @PreAuthorize("hasRole('OPERADOR')")
    public ResponseEntity<Ordem> criar(@AuthenticationPrincipal UsuarioAcesso usuario,@RequestBody Criar dados) {
        Ordem ordem=service.criar(usuario.id(),dados);
        return ResponseEntity.created(URI.create("/api/ordens-servico/"+ordem.id())).body(ordem);
    }
    @PatchMapping("/api/ordens-servico/{id}/status")
    public Ordem status(@PathVariable long id,@AuthenticationPrincipal UsuarioAcesso usuario,@RequestBody AlterarStatus dados) {
        return service.status(id,usuario.id(),dados);
    }
    @GetMapping("/api/setores/{setorId}/tecnicos")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public Pagina<Tecnico> tecnicos(@PathVariable long setorId,@AuthenticationPrincipal UsuarioAcesso usuario,
        @RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamanho) {
        return service.tecnicos(setorId,usuario.id(),pagina,tamanho);
    }
    @PutMapping("/api/setores/{setorId}/tecnicos/{tecnicoId}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public Habilitacao habilitar(@PathVariable long setorId,@PathVariable long tecnicoId,
        @AuthenticationPrincipal UsuarioAcesso usuario,@RequestBody Habilitar dados) {
        return service.habilitar(setorId,tecnicoId,usuario.id(),dados);
    }
}
