package br.agrosense.backend;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LeituraController {

    private final LeituraRepository repositorio;

    public LeituraController(LeituraRepository repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping("/leituras")
    public List<LeituraDto> ultimas() {
        return repositorio.findTop50ByOrderByRecebidaEmDesc().stream()
                .map(LeituraDto::de)
                .toList();
    }

    @GetMapping("/maquinas/{id}/ultima")
    public ResponseEntity<LeituraDto> ultimaDaMaquina(@PathVariable String id) {
        return repositorio.findFirstByMachineIdOrderByRecebidaEmDesc(id)
                .map(l -> ResponseEntity.ok(LeituraDto.de(l)))
                .orElse(ResponseEntity.notFound().build());
    }
}
