package br.agrosense.backend.ordens;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class OrdemDados {
    private OrdemDados() { }
    public record Criar(String maquinaId, String sistema, String impacto, String descricao, String sintomas) { }
    public record AlterarStatus(String acao, Instant versao, String motivo, String diagnostico,
                               String servico, String verificacao, String prioridade, String causa, String reparos, String pecas) { }
    public record Habilitar(Boolean habilitado) { }
    public record Habilitacao(long setorId, long tecnicoId, boolean habilitado) { }
    public record Tecnico(long id, String nome, long ordensAtivas) { }
    public record Pagina<T>(List<T> itens, int pagina, int tamanho, long total) { }
    public record Historico(long id, long usuarioId, String usuarioNome, String evento,
                            Instant ocorridoEm, Map<String,Object> dados) { }
    public record Ordem(long id, String numero, Long alertaId, String maquinaId, String maquinaNome,
                        long setorId, String setorNome, String titulo, String descricao,
                        Map<String,Object> dadosAbertura, String prioridade, Instant prazoPrevisto,
                        String status, String modoAtribuicao, long criadoPor, Long tecnicoId,
                        String tecnicoNome, Long atribuidoPor, Instant atribuidaEm, Instant criadaEm,
                        Instant atualizadaEm, Instant iniciadaEm, Instant concluidaEm,
                        Instant canceladaEm, String relatoFinal, Map<String,Object> parecer, String criadorNome, String parecerGestor, Long prioridadePor, String agravamento) { }
}
