package br.agrosense.backend.cadastros;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR','TECNICO','OPERADOR')")
public class CadastroController {
    private final CadastroService service;
    public CadastroController(CadastroService service) { this.service=service; }
    @GetMapping("/setores")
    public CadastroService.Pagina<CadastroService.Setor> setores(@RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamanho) { return service.setores(pagina,tamanho); }
    @GetMapping("/setores/{id}")
    public CadastroService.Setor setor(@PathVariable long id) { return service.setor(id); }
    @GetMapping("/maquinas")
    public CadastroService.Pagina<CadastroService.Maquina> maquinas(@RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamanho,@RequestParam(required=false) Long setorId) { return service.maquinas(pagina,tamanho,setorId); }
    @GetMapping("/maquinas/{id}")
    public CadastroService.Maquina maquina(@PathVariable String id) { return service.maquina(id); }
    @PostMapping("/setores")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public ResponseEntity<CadastroService.Setor> criarSetor(@RequestBody SetorEntrada dados) {
        var s=service.criarSetor(dados.nome(),dados.descricao(),dados.hectares());
        return ResponseEntity.created(URI.create("/api/setores/"+s.id())).body(s);
    }
    @PutMapping("/setores/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public CadastroService.Setor editarSetor(@PathVariable long id,@RequestBody SetorEntrada dados) { return service.editarSetor(id,dados.nome(),dados.descricao(),dados.hectares()); }
    @PatchMapping("/setores/{id}/situacao")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public CadastroService.Setor situacaoSetor(@PathVariable long id,@RequestBody Situacao dados) { return service.situacaoSetor(id,dados.ativo()); }
    @PostMapping("/maquinas")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public ResponseEntity<CadastroService.Maquina> criarMaquina(@RequestBody NovaMaquina dados) {
        var m=service.criarMaquina(dados.id(),dados.nome(),dados.descricao(),dados.setorId(),dados.modelo());
        return ResponseEntity.created(URI.create("/api/maquinas/"+m.id())).body(m);
    }
    @PutMapping("/maquinas/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public CadastroService.Maquina editarMaquina(@PathVariable String id,@RequestBody EditarMaquina dados) { return service.editarMaquina(id,dados.nome(),dados.descricao(),dados.setorId(),dados.modelo()); }
    @PatchMapping("/maquinas/{id}/situacao")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public CadastroService.Maquina situacaoMaquina(@PathVariable String id,@RequestBody Situacao dados) { return service.situacaoMaquina(id,dados.ativo()); }
    public record SetorEntrada(String nome,String descricao,java.math.BigDecimal hectares) { }
    public record NovaMaquina(String id,String nome,String descricao,Long setorId,String modelo) { }
    public record EditarMaquina(String nome,String descricao,Long setorId,String modelo) { }
    public record Situacao(Boolean ativo) { }
}
