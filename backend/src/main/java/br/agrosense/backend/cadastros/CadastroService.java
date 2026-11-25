package br.agrosense.backend.cadastros;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CadastroService {
    private final JdbcTemplate jdbc;
    private static final Duration LIMITE_ATUALIZACAO = Duration.ofSeconds(30);
    private static final String MAQUINAS = """
        SELECT m.*, s.nome AS setor_nome, s.ativo AS setor_ativo,
               f.situacao, f.sensores::text AS faixas, f.recebida_em AS ultima_leitura_recebida_em, statement_timestamp() AS verificado_em
        FROM public.maquina m JOIN public.setor s ON s.id=m.setor_id
        JOIN public.telemetria_faixas f ON f.maquina_id=m.id
        """;
    public CadastroService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Setor(long id, String nome, String descricao, boolean ativo, Instant criadoEm, java.math.BigDecimal hectares) { }
    public record Maquina(String id, String nome, String descricao, long setorId, String setorNome,
                          boolean ativa, boolean setorAtivo, Instant criadaEm, String statusAtualizacao,
                          Instant ultimaLeituraRecebidaEm, Long segundosSemAtualizacao, Instant verificadoEm, String situacaoFaixas, List<Map<String,Object>> faixas, String modelo) { }
    public record Pagina<T>(List<T> itens, int pagina, int tamanho, long total) { }
    public static class Erro extends RuntimeException {
        private final int status;
        private final String codigo;
        public Erro(int status, String codigo, String mensagem) { super(mensagem); this.status=status; this.codigo=codigo; }
        public int status() { return status; }
        public String codigo() { return codigo; }
    }
    private static Setor setor(ResultSet r, int i) throws SQLException {
        return new Setor(r.getLong("id"),r.getString("nome"),r.getString("descricao"),r.getBoolean("ativo"),r.getTimestamp("criado_em").toInstant(),r.getBigDecimal("hectares"));
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String,Object>>> TIPO_FAIXAS = new TypeReference<>() {};
    private static List<Map<String,Object>> faixas(ResultSet r) throws SQLException {
        try { return JSON.readValue(r.getString("faixas"),TIPO_FAIXAS); }
        catch (java.io.IOException e) { throw new SQLException("Faixas inválidas",e); }
    }
    private static Maquina maquina(ResultSet r, int i) throws SQLException {
        boolean ativa=r.getBoolean("ativa"),setorAtivo=r.getBoolean("setor_ativo");
        Instant agora=r.getTimestamp("verificado_em").toInstant();
        var recebida=r.getTimestamp("ultima_leitura_recebida_em");
        Instant ultima=recebida==null?null:recebida.toInstant();
        Duration intervalo=ultima==null?null:Duration.between(ultima,agora);
        Long segundos=intervalo==null?null:Math.max(0,intervalo.getSeconds());
        String status=!ativa||!setorAtivo?"INATIVA":ultima==null?"SEM_LEITURAS"
            :intervalo.compareTo(LIMITE_ATUALIZACAO)>=0?"SEM_ATUALIZACAO":"ATUALIZADA";
        return new Maquina(r.getString("id"),r.getString("nome"),r.getString("descricao"),r.getLong("setor_id"),
            r.getString("setor_nome"),ativa,setorAtivo,r.getTimestamp("criada_em").toInstant(),status,ultima,segundos,agora,r.getString("situacao"),faixas(r),r.getString("modelo"));
    }
    public Pagina<Setor> setores(int pagina, int tamanho) {
        pagina(pagina,tamanho);
        return new Pagina<>(jdbc.query("SELECT * FROM public.setor ORDER BY id LIMIT ? OFFSET ?",CadastroService::setor,tamanho,(long)pagina*tamanho),pagina,tamanho,jdbc.queryForObject("SELECT count(*) FROM public.setor",Long.class));
    }
    public Pagina<Maquina> maquinas(int pagina, int tamanho, Long setorId) {
        pagina(pagina,tamanho);
        if(setorId!=null) idSetor(setorId);
        String filtro=setorId==null?"":" WHERE m.setor_id=?";
        List<Maquina> itens=setorId==null
            ?jdbc.query(MAQUINAS+" ORDER BY m.id LIMIT ? OFFSET ?",CadastroService::maquina,tamanho,(long)pagina*tamanho)
            :jdbc.query(MAQUINAS+filtro+" ORDER BY m.id LIMIT ? OFFSET ?",CadastroService::maquina,setorId,tamanho,(long)pagina*tamanho);
        long total=setorId==null?jdbc.queryForObject("SELECT count(*) FROM public.maquina",Long.class):jdbc.queryForObject("SELECT count(*) FROM public.maquina WHERE setor_id=?",Long.class,setorId);
        return new Pagina<>(itens,pagina,tamanho,total);
    }
    public Setor setor(long id) { return setor(id,false); }
    private Setor setor(long id, boolean bloquear) {
        idSetor(id);
        return jdbc.query("SELECT * FROM public.setor WHERE id=?"+(bloquear?" FOR UPDATE":""),CadastroService::setor,id).stream().findFirst().orElseThrow(()->new Erro(404,"SETOR_NAO_ENCONTRADO","Setor nao encontrado"));
    }
    public Maquina maquina(String id) {
        idMaquina(id);
        return jdbc.query(MAQUINAS+" WHERE m.id=?",CadastroService::maquina,id).stream().findFirst().orElseThrow(()->new Erro(404,"MAQUINA_NAO_ENCONTRADA","Maquina nao encontrada"));
    }
    @Transactional
    public Setor criarSetor(String nome, String descricao, java.math.BigDecimal hectares) {
        if (hectares != null && hectares.signum()<0) throw invalido("Área negativa");
        nome=nome(nome,100); descricao=descricao(descricao);
        Long id=jdbc.queryForObject("INSERT INTO public.setor(nome,descricao,hectares) VALUES (?,?,?) RETURNING id",Long.class,nome,descricao,hectares);
        return setor(id);
    }
    @Transactional
    public Setor editarSetor(long id, String nome, String descricao, java.math.BigDecimal hectares) {
        if (hectares != null && hectares.signum()<0) throw invalido("Área negativa");
        nome=nome(nome,100); descricao=descricao(descricao); setor(id,true);
        jdbc.update("UPDATE public.setor SET nome=?,descricao=?,hectares=COALESCE(?,hectares) WHERE id=?",nome,descricao,hectares,id);
        return setor(id);
    }
    @Transactional
    public Setor situacaoSetor(long id, Boolean ativo) {
        situacao(ativo); setor(id,true);
        jdbc.update("UPDATE public.setor SET ativo=? WHERE id=?",ativo,id);
        return setor(id);
    }
    @Transactional
    public Maquina criarMaquina(String id, String nome, String descricao, Long setorId, String modelo) {
        if (modelo != null && modelo.length()>160) throw invalido("Modelo excede 160 caracteres");
        idMaquina(id); nome=nome(nome,120); descricao=descricao(descricao); setorAtivo(setorId);
        jdbc.update("INSERT INTO public.maquina(id,nome,descricao,setor_id,modelo) VALUES (?,?,?,?,?)",id,nome,descricao,setorId,modelo);
        return maquina(id);
    }
    @Transactional
    public Maquina editarMaquina(String id, String nome, String descricao, Long setorId, String modelo) {
        if (modelo != null && modelo.length()>160) throw invalido("Modelo excede 160 caracteres");
        idMaquina(id); nome=nome(nome,120); descricao=descricao(descricao); setorAtivo(setorId);
        if(jdbc.update("UPDATE public.maquina SET nome=?,descricao=?,setor_id=?,modelo=COALESCE(?,modelo) WHERE id=?",nome,descricao,setorId,modelo,id)==0)
            throw new Erro(404,"MAQUINA_NAO_ENCONTRADA","Maquina nao encontrada");
        return maquina(id);
    }
    @Transactional
    public Maquina situacaoMaquina(String id, Boolean ativa) {
        situacao(ativa); idMaquina(id);
        if(jdbc.update("UPDATE public.maquina SET ativa=? WHERE id=?",ativa,id)==0)
            throw new Erro(404,"MAQUINA_NAO_ENCONTRADA","Maquina nao encontrada");
        return maquina(id);
    }
    private void setorAtivo(Long id) {
        if(id==null) throw invalido("Informe setorId");
        if(!setor(id,true).ativo()) throw new Erro(409,"SETOR_INATIVO","Escolha um setor ativo");
    }
    private static Erro invalido(String mensagem) { return new Erro(400,"DADOS_INVALIDOS",mensagem); }
    private static void idSetor(long id) { if(id<=0) throw invalido("setorId deve ser positivo"); }
    private static void idMaquina(String id) {
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,29}")) throw invalido("Codigo deve ter de 1 a 30 letras ASCII, numeros, hifen ou sublinhado, comecando por letra ou numero");
    }
    private static String nome(String nome,int max) {
        if(nome==null||nome.isBlank()||nome.strip().length()>max||nome.chars().anyMatch(Character::isISOControl)) throw invalido("Informe um nome de ate "+max+" caracteres");
        return nome.strip();
    }
    private static String descricao(String texto) {
        if(texto==null||texto.isBlank()) return null;
        if(texto.length()>500||texto.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')) throw invalido("Descricao deve ter ate 500 caracteres");
        return texto.strip();
    }
    private static void situacao(Boolean valor) { if(valor==null) throw invalido("Informe ativo como true ou false"); }
    private static void pagina(int pagina,int tamanho) { if(pagina<0||tamanho<1||tamanho>100) throw invalido("pagina deve ser >= 0 e tamanho entre 1 e 100"); }
}
