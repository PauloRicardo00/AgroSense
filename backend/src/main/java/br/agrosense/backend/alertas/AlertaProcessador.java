package br.agrosense.backend.alertas;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import br.agrosense.backend.Leitura;
import br.agrosense.backend.LeituraRepository;

@Service
public class AlertaProcessador {
    private final JdbcTemplate jdbc;
    private final LeituraRepository leituras;
    public AlertaProcessador(JdbcTemplate jdbc,LeituraRepository leituras) { this.jdbc=jdbc; this.leituras=leituras; }

    @Transactional
    public void finalizar(Leitura leitura) {
        jdbc.queryForObject("SELECT id FROM public.maquina WHERE id=? FOR UPDATE",String.class,leitura.getMachineId());
        String status=jdbc.queryForObject("SELECT status_analise FROM public.leitura WHERE id=? FOR UPDATE",String.class,leitura.getId());
        if(!"PENDENTE".equals(status)) return;
        if("OK".equals(leitura.getStatusAnalise())) {
            var instante=leitura.getTimestamp()!=null?leitura.getTimestamp():leitura.getRecebidaEm();
            Boolean posterior=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.leitura
                WHERE machine_id=? AND status_analise='OK' AND id<>?
                AND (coalesce(medida_em,recebida_em),id) > (CAST(? AS timestamptz),CAST(? AS bigint)))
                """,Boolean.class,leitura.getMachineId(),leitura.getId(),Timestamp.from(instante),leitura.getId());
            if(!Boolean.TRUE.equals(posterior)) atualizarEpisodio(leitura);
        }
        leituras.saveAndFlush(leitura);
    }

    private void atualizarEpisodio(Leitura leitura) {
        var ids=jdbc.queryForList("SELECT id FROM public.alerta WHERE maquina_id=? AND status<>'ENCERRADO' FOR UPDATE",Long.class,leitura.getMachineId());
        if(Boolean.TRUE.equals(leitura.getFalha())) {
            if(ids.isEmpty()) {
                jdbc.update("""
                    INSERT INTO public.alerta(maquina_id,leitura_inicial_id,ultima_leitura_id,score_maximo,versao_modelo)
                    VALUES (?,?,?,?,?)
                    """,leitura.getMachineId(),leitura.getId(),leitura.getId(),leitura.getScoreFalha(),leitura.getVersaoModelo());
            } else {
                jdbc.update("""
                    UPDATE public.alerta SET ultima_leitura_id=?,score_maximo=greatest(score_maximo,?),
                    ultima_ocorrencia_em=greatest(clock_timestamp(),aberto_em,ultima_ocorrencia_em,reconhecido_em)
                    WHERE id=?
                    """,leitura.getId(),leitura.getScoreFalha(),ids.getFirst());
            }
        } else if(!ids.isEmpty()) {
            jdbc.update("""
                UPDATE public.alerta SET status='ENCERRADO',
                encerrado_em=greatest(clock_timestamp(),aberto_em,ultima_ocorrencia_em,reconhecido_em)
                WHERE id=?
                """,ids.getFirst());
        }
    }
}
