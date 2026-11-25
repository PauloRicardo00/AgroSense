package br.agrosense.backend.alertas;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertaReconhecimento {
    private final JdbcTemplate jdbc;
    private final AlertaConsulta consulta;

    public AlertaReconhecimento(JdbcTemplate jdbc, AlertaConsulta consulta) {
        this.jdbc = jdbc;
        this.consulta = consulta;
    }

    @Transactional
    public AlertaConsulta.Alerta reconhecer(long id, long usuarioId, String observacao) {
        if (id <= 0) throw new AlertaConsulta.Erro(400, "DADOS_INVALIDOS", "Informe um id positivo");
        if (observacao != null) {
            observacao = observacao.strip();
            if (observacao.length() > 1000)
                throw new AlertaConsulta.Erro(400, "DADOS_INVALIDOS", "Observacao deve ter no maximo 1000 caracteres");
            if (observacao.isEmpty()) observacao = null;
        }
        var usuarios = jdbc.queryForList("""
            SELECT id FROM public.usuario
            WHERE id=? AND ativo=true AND perfil IN ('ADMIN','GESTOR','TECNICO') FOR SHARE
            """, Long.class, usuarioId);
        if (usuarios.isEmpty())
            throw new AlertaConsulta.Erro(403, "SEM_PERMISSAO", "Voce nao tem permissao para esta operacao");

        var estados = jdbc.queryForList("SELECT status FROM public.alerta WHERE id=? FOR UPDATE", String.class, id);
        if (estados.isEmpty())
            throw new AlertaConsulta.Erro(404, "ALERTA_NAO_ENCONTRADO", "Alerta nao encontrado");
        if ("ENCERRADO".equals(estados.getFirst()))
            throw new AlertaConsulta.Erro(409, "ALERTA_ENCERRADO", "Nao e possivel reconhecer um alerta encerrado");
        if ("ABERTO".equals(estados.getFirst())) {
            jdbc.update("""
                UPDATE public.alerta SET status='RECONHECIDO', reconhecido_por=?,
                reconhecido_em=greatest(clock_timestamp(),aberto_em,ultima_ocorrencia_em), observacao=?
                WHERE id=?
                """, usuarioId, observacao, id);
        }
        return consulta.buscar(id);
    }
}
