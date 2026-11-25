package br.agrosense.backend.alertas;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import br.agrosense.backend.seguranca.UsuarioAcesso;

@RestController
@RequestMapping("/api/alertas")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR','TECNICO','OPERADOR')")
public class AlertaController {
    private final AlertaConsulta consulta;
    private final AlertaReconhecimento reconhecimento;
    public AlertaController(AlertaConsulta consulta, AlertaReconhecimento reconhecimento) {
        this.consulta=consulta;
        this.reconhecimento=reconhecimento;
    }
    public record Reconhecimento(String observacao) { }
    @GetMapping
    public AlertaConsulta.Pagina listar(@RequestParam(defaultValue="0") int pagina,@RequestParam(defaultValue="20") int tamanho,@RequestParam(required=false) String maquinaId,@RequestParam(required=false) String status) { return consulta.listar(pagina,tamanho,maquinaId,status); }
    @GetMapping("/{id}")
    public AlertaConsulta.Alerta buscar(@PathVariable long id) { return consulta.buscar(id); }
    @PostMapping("/{id}/reconhecimento")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR','TECNICO')")
    public AlertaConsulta.Alerta reconhecer(@PathVariable long id, @AuthenticationPrincipal UsuarioAcesso usuario,
                                           @RequestBody(required=false) Reconhecimento dados) {
        return reconhecimento.reconhecer(id, usuario.id(), dados==null?null:dados.observacao());
    }
}
