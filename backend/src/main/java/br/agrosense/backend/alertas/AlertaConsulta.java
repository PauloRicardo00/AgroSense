package br.agrosense.backend.alertas;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlertaConsulta {
    private final JdbcTemplate jdbc;
    public AlertaConsulta(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Alerta(long id,String maquinaId,String status,Instant abertoEm,Instant ultimaOcorrenciaEm,Instant encerradoEm,long leituraInicialId,long ultimaLeituraId,double scoreMaximo,String versaoModelo,Long reconhecidoPor,Instant reconhecidoEm,String observacao) { }
    public record Pagina(List<Alerta> itens,int pagina,int tamanho,long total) { }
    public static class Erro extends RuntimeException {
        public final int status;
        public final String codigo;
        public Erro(int status,String codigo,String mensagem) { super(mensagem);this.status=status;this.codigo=codigo; }
    }
    private static Instant instante(ResultSet r,String campo) throws SQLException { var t=r.getTimestamp(campo);return t==null?null:t.toInstant(); }
    private static Alerta mapear(ResultSet r,int i) throws SQLException {
        return new Alerta(r.getLong("id"),r.getString("maquina_id"),r.getString("status"),instante(r,"aberto_em"),instante(r,"ultima_ocorrencia_em"),instante(r,"encerrado_em"),r.getLong("leitura_inicial_id"),r.getLong("ultima_leitura_id"),r.getDouble("score_maximo"),r.getString("versao_modelo"),r.getObject("reconhecido_por",Long.class),instante(r,"reconhecido_em"),r.getString("observacao"));
    }
    public Pagina listar(int pagina,int tamanho,String maquinaId,String status) {
        if(pagina<0||tamanho<1||tamanho>100) throw new Erro(400,"DADOS_INVALIDOS","pagina >= 0 e tamanho entre 1 e 100");
        String filtro=" WHERE 1=1";var valores=new ArrayList<Object>();
        if(maquinaId!=null) {
            if(maquinaId.isBlank()||maquinaId.length()>30) throw new Erro(400,"DADOS_INVALIDOS","maquinaId invalido");
            filtro+=" AND maquina_id=?";valores.add(maquinaId);
        }
        if(status!=null) {
            if(!Set.of("ABERTO","RECONHECIDO","ENCERRADO").contains(status)) throw new Erro(400,"DADOS_INVALIDOS","Status deve ser ABERTO, RECONHECIDO ou ENCERRADO");
            filtro+=" AND status=?";valores.add(status);
        }
        long total=jdbc.queryForObject("SELECT count(*) FROM public.alerta"+filtro,Long.class,valores.toArray());
        valores.add(tamanho);valores.add((long)pagina*tamanho);
        var itens=jdbc.query("SELECT * FROM public.alerta"+filtro+" ORDER BY aberto_em DESC,id DESC LIMIT ? OFFSET ?",AlertaConsulta::mapear,valores.toArray());
        return new Pagina(itens,pagina,tamanho,total);
    }
    public Alerta buscar(long id) {
        if(id<=0) throw new Erro(400,"DADOS_INVALIDOS","Informe um id positivo");
        return jdbc.query("SELECT * FROM public.alerta WHERE id=?",AlertaConsulta::mapear,id).stream().findFirst().orElseThrow(()->new Erro(404,"ALERTA_NAO_ENCONTRADO","Alerta nao encontrado"));
    }
}
