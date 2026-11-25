package br.agrosense.backend.ordens;

import static br.agrosense.backend.ordens.OrdemDados.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrdemService {
    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> ESTADOS=Set.of("AGUARDANDO_AUTORIZACAO","AUTORIZADA","AGUARDANDO_ENCERRAMENTO","RECUSADA","EM_ANDAMENTO","CONCLUIDA","CANCELADA");
    private static final String SELECT="""
        SELECT o.*,coalesce(o.dados_abertura->'maquina'->>'nome',m.nome) AS maquina_nome,
            coalesce(o.dados_abertura->>'setorNome',s.nome) AS setor_nome,u.nome AS tecnico_nome,coalesce(o.dados_abertura->>'operador',criador.nome) AS criador_nome,
            (SELECT h.dados->>'motivo' FROM public.os_historico h WHERE h.os_id=o.id AND h.evento IN ('DEVOLUCAO','RECUSA','CONCLUSAO','AUTORIZACAO','PARECER') ORDER BY h.id DESC LIMIT 1) AS parecer_gestor,
            (SELECT CASE WHEN h.evento='REAVALIACAO' THEN h.dados->>'motivo' END FROM public.os_historico h
             WHERE h.os_id=o.id AND h.evento IN ('REAVALIACAO','PRIORIDADE','CONCLUSAO') ORDER BY h.id DESC LIMIT 1) AS agravamento
        FROM public.ordem_servico o JOIN public.maquina m ON m.id=o.maquina_id
        JOIN public.usuario criador ON criador.id=o.criado_por JOIN public.setor s ON s.id=o.setor_id LEFT JOIN public.usuario u ON u.id=o.tecnico_id
        """;
    public OrdemService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    private static OrdemErro invalido(String mensagem) { return new OrdemErro(400,"DADOS_INVALIDOS",mensagem); }
    private static OrdemErro proibido() { return new OrdemErro(403,"SEM_PERMISSAO","Voce nao tem permissao para esta operacao"); }
    private static void id(Long id) { if(id==null||id<=0) throw invalido("Informe um identificador positivo"); }
    private static void pagina(int pagina,int tamanho) {
        if(pagina<0||tamanho<1||tamanho>100) throw invalido("pagina deve ser >= 0 e tamanho entre 1 e 100");
    }
    private static Instant instante(ResultSet r,String coluna) throws SQLException {
        var t=r.getTimestamp(coluna);return t==null?null:t.toInstant();
    }
    private static Map<String,Object> json(String valor) {
        try { return JSON.readValue(valor,new TypeReference<Map<String,Object>>() { }); }
        catch(java.io.IOException e) { throw new IllegalStateException("Dados de historico invalidos",e); }
    }
    private static Ordem mapear(ResultSet r,int linha) throws SQLException {
        return new Ordem(r.getLong("id"),r.getString("numero"),r.getObject("alerta_id",Long.class),r.getString("maquina_id"),
            r.getString("maquina_nome"),r.getLong("setor_id"),r.getString("setor_nome"),r.getString("titulo"),
            r.getString("descricao"),json(r.getString("dados_abertura")),r.getString("prioridade"),instante(r,"prazo_previsto"),
            etapa(r.getString("status")),r.getString("modo_atribuicao"),r.getLong("criado_por"),r.getObject("tecnico_id",Long.class),
            r.getString("tecnico_nome"),r.getObject("atribuido_por",Long.class),instante(r,"atribuida_em"),
            instante(r,"criada_em"),instante(r,"atualizada_em"),instante(r,"iniciada_em"),instante(r,"concluida_em"),
            instante(r,"cancelada_em"),r.getString("relato_final"),json(r.getString("parecer")),r.getString("criador_nome"),r.getString("parecer_gestor"),r.getObject("prioridade_por",Long.class),r.getString("agravamento"));
    }
    private String perfil(long ator,boolean bloquear) {
        var perfis=jdbc.queryForList("SELECT perfil FROM public.usuario WHERE id=? AND ativo=true"+(bloquear?" FOR SHARE":""),String.class,ator);
        if(perfis.isEmpty()||!Set.of("ADMIN","GESTOR","TECNICO","OPERADOR").contains(perfis.getFirst())) throw proibido();
        return perfis.getFirst();
    }
    private static void gerente(String perfil) { if(!Set.of("ADMIN","GESTOR").contains(perfil)) throw proibido(); }
    private String iniciar(long ator,boolean gestao) {
        jdbc.execute("SET LOCAL search_path=pg_catalog,public");
        jdbc.execute("SELECT pg_advisory_xact_lock(80321,1)");
        String perfil=perfil(ator,true);
        if(gestao) gerente(perfil);
        return perfil;
    }
    private Ordem buscar(long id,long ator,String perfil) {
        id(id);
        String filtro=" WHERE o.id=?";var valores=new ArrayList<Object>();valores.add(id);
        if("TECNICO".equals(perfil)) { filtro+=" AND ((o.tecnico_id=? AND o.status NOT IN ('ABERTA','AGUARDANDO_ATRIBUICAO')) OR o.status='AUTORIZADA')";valores.add(ator); }
        return jdbc.query(SELECT+filtro,OrdemService::mapear,valores.toArray()).stream().findFirst()
            .orElseThrow(()->new OrdemErro(404,"ORDEM_NAO_ENCONTRADA","Ordem de servico nao encontrada para este usuario"));
    }
    public Ordem buscar(long id,long ator) { return buscar(id,ator,perfil(ator,false)); }
    public Pagina<Ordem> listar(long ator,int pagina,int tamanho,String status,String maquinaId) {
        pagina(pagina,tamanho);
        if(status!=null&&!ESTADOS.contains(status)) throw invalido("Status de ordem invalido");
        if(maquinaId!=null&&(maquinaId.isBlank()||maquinaId.length()>30)) throw invalido("maquinaId invalido");
        String perfil=perfil(ator,false),filtro=" WHERE 1=1";var valores=new ArrayList<Object>();
        if("TECNICO".equals(perfil)) { filtro+=" AND ((o.tecnico_id=? AND o.status NOT IN ('ABERTA','AGUARDANDO_ATRIBUICAO')) OR o.status='AUTORIZADA')";valores.add(ator); }
        if(status!=null) { filtro+=" AND (CASE WHEN o.status IN ('ABERTA','AGUARDANDO_ATRIBUICAO') THEN 'AGUARDANDO_AUTORIZACAO' ELSE o.status END)=?";valores.add(status); }
        if(maquinaId!=null) { filtro+=" AND o.maquina_id=?";valores.add(maquinaId); }
        long total=jdbc.queryForObject("SELECT count(*) FROM public.ordem_servico o"+filtro,Long.class,valores.toArray());
        valores.add(tamanho);valores.add((long)pagina*tamanho);
        return new Pagina<>(jdbc.query(SELECT+filtro+" ORDER BY CASE WHEN o.status='AUTORIZADA' THEN 0 ELSE 1 END, CASE WHEN o.status='AUTORIZADA' AND o.prioridade_por IS NOT NULL THEN CASE o.prioridade WHEN 'CRITICA' THEN 0 WHEN 'ALTA' THEN 1 WHEN 'MEDIA' THEN 2 ELSE 3 END ELSE 4 END, CASE WHEN o.status='AUTORIZADA' THEN o.criada_em END ASC, o.criada_em DESC,o.id ASC LIMIT ? OFFSET ?",OrdemService::mapear,valores.toArray()),pagina,tamanho,total);
    }
    public Pagina<Historico> historico(long id,long ator,int pagina,int tamanho) {
        pagina(pagina,tamanho);buscar(id,ator);
        long total=jdbc.queryForObject("SELECT count(*) FROM public.os_historico WHERE os_id=?",Long.class,id);
        var itens=jdbc.query("""
            SELECT h.*,coalesce(h.dados->>'autorNome',u.nome) AS usuario_nome FROM public.os_historico h
            JOIN public.usuario u ON u.id=h.usuario_id WHERE os_id=? ORDER BY h.id LIMIT ? OFFSET ?
            """,(r,i)->new Historico(r.getLong("id"),r.getLong("usuario_id"),r.getString("usuario_nome"),r.getString("evento"),
                instante(r,"ocorrido_em"),json(r.getString("dados"))),id,tamanho,(long)pagina*tamanho);
        return new Pagina<>(itens,pagina,tamanho,total);
    }
    private static String etapa(String status) {
        return switch (status) {
            case "ABERTA", "AGUARDANDO_ATRIBUICAO" -> "AGUARDANDO_AUTORIZACAO";
            default -> status;
        };
    }

    private static String texto(String valor, String campo, int limite) {
        if (valor == null || valor.isBlank() || valor.strip().length() > limite)
            throw invalido("Preencha " + campo + " com até " + limite + " caracteres.");
        return valor.strip();
    }

    private static String serializar(Map<String, ?> dados) {
        try { return JSON.writeValueAsString(dados); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private void registrar(long ordem, long ator, String evento, Map<String, ?> dados) {
        jdbc.update("""
            INSERT INTO public.os_historico (os_id,usuario_id,evento,dados,ocorrido_em)
            SELECT ?,u.id,?,?::jsonb || jsonb_build_object('autorNome',u.nome,'autorPerfil',u.perfil),clock_timestamp()
            FROM public.usuario u WHERE u.id=?
            """, ordem, evento, serializar(dados), ator);
    }

    @Transactional
    public Ordem criar(long ator, Criar pedido) {
        if (!"OPERADOR".equals(iniciar(ator,false))) throw proibido();
        if (pedido == null) throw invalido("Informe os dados da solicitação.");
        String maquina = texto(pedido.maquinaId(),"a máquina",30);
        String sistema = texto(pedido.sistema(),"o sistema",80);
        String impacto = texto(pedido.impacto(),"o impacto",80);
        String sintomas = texto(pedido.sintomas(),"os sintomas",2000);
        String descricao = texto(pedido.descricao(),"o problema observado",2000);
        if (!Set.of("Motor","Transmissão","Hidráulico","Elétrico","Implemento","Outro / não identificado").contains(sistema)
                || !Set.of("Máquina parada","Operação limitada","Sem interrupção").contains(impacto))
            throw invalido("Escolha um sistema e um impacto válidos.");
        var ids = jdbc.queryForList("""
            INSERT INTO public.ordem_servico
                (maquina_id,setor_id,alerta_id,titulo,descricao,criado_por,status,dados_abertura)
            SELECT m.id,s.id,a.id,'Manutenção - '||m.nome,?,?,'AGUARDANDO_AUTORIZACAO',
                jsonb_build_object('maquina',to_jsonb(m),'setorNome',s.nome,'sistema',?::text,'impacto',?::text,
                    'sintomas',?::text,'faixas',f.sensores,'situacaoFaixas',f.situacao,'operador',u.nome,'alerta_id',a.id,'leitura',to_jsonb(l),
                    'classificacao',CASE WHEN f.situacao='CRITICO' OR a.status='ABERTO' THEN 'Crítico'
                        WHEN f.situacao='ATENCAO' OR a.status='RECONHECIDO' OR NOT m.ativa OR NOT s.ativo OR l.id IS NULL
                            OR l.recebida_em <= statement_timestamp()-interval '30 seconds'
                            OR l.status_analise <> 'OK' THEN 'Atenção'
                        WHEN l.falha THEN 'Crítico' ELSE 'Normal' END,
                    'irregularidades',concat_ws(' · ',
                        CASE a.status WHEN 'ABERTO' THEN 'Alerta aberto #'||a.id
                            WHEN 'RECONHECIDO' THEN 'Alerta reconhecido #'||a.id END,
                        CASE WHEN NOT m.ativa OR NOT s.ativo THEN 'Cadastro inativo' END,
                        CASE WHEN l.id IS NULL THEN 'Sem leituras'
                            WHEN l.recebida_em <= statement_timestamp()-interval '30 seconds' THEN 'Sem atualização recente'
                            WHEN l.status_analise <> 'OK' THEN 'Leitura sem análise concluída'
                            WHEN l.falha THEN 'Indicação de falha na última leitura' END))
            FROM public.maquina m JOIN public.setor s ON s.id=m.setor_id
            JOIN public.usuario u ON u.id=? JOIN public.telemetria_faixas f ON f.maquina_id=m.id
            LEFT JOIN public.alerta a ON a.maquina_id=m.id AND a.status<>'ENCERRADO'
            LEFT JOIN LATERAL (SELECT * FROM public.leitura WHERE machine_id=m.id
                ORDER BY recebida_em DESC,id DESC LIMIT 1) l ON true
            WHERE m.id=? RETURNING id
            """, Long.class, descricao, ator, sistema, impacto, sintomas, ator, maquina);
        if (ids.isEmpty()) throw invalido("Máquina não encontrada.");
        long ordem = ids.getFirst();
        registrar(ordem,ator,"CRIACAO",Map.of("atual","AGUARDANDO_AUTORIZACAO"));
        return buscar(ordem,ator,"OPERADOR");
    }

    private static String prioridade(String valor) {
        if (valor == null || !Set.of("BAIXA","MEDIA","ALTA","CRITICA").contains(valor))
            throw invalido("Selecione a prioridade da manutenção.");
        return valor;
    }

    private static void exigir(String perfil, String esperado, Ordem ordem, String estado) {
        if (!esperado.equals(perfil)) throw proibido();
        if (!estado.equals(ordem.status())) throw new OrdemErro(409,"ETAPA_INVALIDA","A ordem mudou de etapa. Atualize a lista.");
    }

    @Transactional
    public Ordem status(long id, long ator, AlterarStatus pedido) {
        String perfil = iniciar(ator,false);
        if (pedido == null || pedido.acao() == null || pedido.versao() == null)
            throw invalido("Informe a ação e a versão da ordem.");
        Ordem ordem = buscar(id,ator,perfil);
        if (!ordem.atualizadaEm().equals(pedido.versao()))
            throw new OrdemErro(409,"ETAPA_INVALIDA","A ordem foi atualizada. Confira a etapa atual.");
        String seguinte, evento, campos = "";
        var valores = new ArrayList<Object>();
        var historico = new java.util.LinkedHashMap<String,Object>();
        String motivo = pedido.motivo() == null ? "" : pedido.motivo().strip();
        if (motivo.length() > 2000) throw invalido("O parecer do gestor deve ter até 2000 caracteres.");
        if (pedido.prioridade() != null && !Set.of("autorizar","priorizar").contains(pedido.acao()))
            throw proibido();
        switch (pedido.acao()) {
            case "autorizar" -> {
                exigir(perfil,"GESTOR",ordem,"AGUARDANDO_AUTORIZACAO");
                seguinte="AUTORIZADA"; evento="AUTORIZACAO";
                valores.add(prioridade(pedido.prioridade())); valores.add(ator);
                campos=", prioridade=?, prioridade_por=?, tecnico_id=NULL, atribuido_por=NULL, atribuida_em=NULL, modo_atribuicao=NULL";
            }
            case "priorizar" -> {
                if (!"GESTOR".equals(perfil)) throw proibido();
                if (!Set.of("AUTORIZADA","EM_ANDAMENTO","AGUARDANDO_ENCERRAMENTO").contains(ordem.status()))
                    throw new OrdemErro(409,"ETAPA_INVALIDA","A prioridade só pode ser revista após a autorização e enquanto a ordem está ativa.");
                String nova=prioridade(pedido.prioridade());
                if (ordem.prioridadePor()!=null && nova.equals(ordem.prioridade()))
                    throw invalido("Escolha uma prioridade diferente da atual.");
                texto(motivo,"a justificativa",2000);
                seguinte=ordem.status(); evento="PRIORIDADE";
                campos=", prioridade=?, prioridade_por=?"; valores.add(nova); valores.add(ator);
            }
            case "reavaliar" -> {
                exigir(perfil,"TECNICO",ordem,"EM_ANDAMENTO");
                if (!Long.valueOf(ator).equals(ordem.tecnicoId())) throw proibido();
                texto(motivo,"o agravamento observado",2000);
                seguinte=ordem.status(); evento="REAVALIACAO"; historico.put("motivo",motivo);
            }
            case "recusar" -> {
                exigir(perfil,"GESTOR",ordem,"AGUARDANDO_AUTORIZACAO");
                texto(motivo,"o motivo da recusa",2000);
                seguinte="RECUSADA"; evento="RECUSA";
            }
            case "assumir" -> {
                exigir(perfil,"TECNICO",ordem,"AUTORIZADA");
                if (ordem.prioridadePor()==null)
                    throw new OrdemErro(409,"SEM_PRIORIDADE","Aguarde o gestor definir a prioridade.");
                seguinte="EM_ANDAMENTO"; evento="INICIO";
                campos=", tecnico_id=?, atribuido_por=?, atribuida_em=clock_timestamp(), iniciada_em=clock_timestamp(), modo_atribuicao='MANUAL'";
                valores.add(ator); valores.add(ator);
            }
            case "enviar" -> {
                exigir(perfil,"TECNICO",ordem,"EM_ANDAMENTO");
                if (!Long.valueOf(ator).equals(ordem.tecnicoId())) throw proibido();
                var pecas=pedido.pecas()==null ? "" : pedido.pecas().strip();
                if (pecas.length()>2000) throw invalido("Peças utilizadas deve ter até 2000 caracteres.");
                var parecer=Map.of("causa",texto(pedido.causa(),"a causa identificada",2000),
                    "reparos",texto(pedido.reparos(),"os reparos e ajustes",2000),"pecas",pecas,"diagnostico",texto(pedido.diagnostico(),"o diagnóstico",2000),
                    "servico",texto(pedido.servico(),"o serviço realizado",4000),
                    "verificacao",texto(pedido.verificacao(),"o teste de verificação",2000));
                historico.putAll(parecer);
                seguinte="AGUARDANDO_ENCERRAMENTO"; evento="PARECER";
                campos=", parecer=?::jsonb"; valores.add(serializar(parecer));
            }
            case "devolver" -> {
                exigir(perfil,"GESTOR",ordem,"AGUARDANDO_ENCERRAMENTO");
                texto(motivo,"o motivo da devolução",2000);
                seguinte="EM_ANDAMENTO"; evento="DEVOLUCAO";
            }
            case "concluir" -> {
                exigir(perfil,"GESTOR",ordem,"AGUARDANDO_ENCERRAMENTO");
                seguinte="CONCLUIDA"; evento="CONCLUSAO";
                campos=", concluida_em=clock_timestamp(), relato_final=?";
                var p=ordem.parecer();
                valores.add("Diagnóstico: "+p.get("diagnostico")+"\nServiço: "+p.get("servico")+"\nVerificação: "+p.get("verificacao"));
            }
            default -> throw invalido("Ação de manutenção desconhecida.");
        }
        valores.add(0,seguinte); valores.add(id); valores.add(Timestamp.from(pedido.versao()));
        int alteradas=jdbc.update("UPDATE public.ordem_servico SET status=?, atualizada_em=clock_timestamp()"+campos+
            " WHERE id=? AND atualizada_em=?",valores.toArray());
        if (alteradas != 1) throw new OrdemErro(409,"ETAPA_INVALIDA","A ordem mudou. Atualize a lista.");
        historico.put("anterior",ordem.status()); historico.put("atual",seguinte);
        if ("GESTOR".equals(perfil) && !motivo.isEmpty()) historico.put("motivo",motivo);
        if ("PRIORIDADE".equals(evento)) {
            historico.put("prioridadeAnterior",ordem.prioridadePor()==null ? "SEM_DEFINICAO" : ordem.prioridade());
            historico.put("prioridadeNova",pedido.prioridade());
        }
        registrar(id,ator,evento,historico);
        if ("AUTORIZACAO".equals(evento))
            registrar(id,ator,"PRIORIDADE",Map.of("prioridadeAnterior","SEM_DEFINICAO","prioridadeNova",pedido.prioridade(),
                "atual",seguinte));
        if ("PARECER".equals(evento))
            registrar(id,ator,"SOLICITACAO_ENCERRAMENTO",Map.of("atual",seguinte));
        return buscar(id,ator,perfil);
    }
    public Pagina<Tecnico> tecnicos(long setorId,long ator,int pagina,int tamanho) {
        id(setorId);pagina(pagina,tamanho);gerente(perfil(ator,false));
        if(jdbc.queryForList("SELECT id FROM public.setor WHERE id=?",Long.class,setorId).isEmpty()) throw new OrdemErro(404,"SETOR_NAO_ENCONTRADO","Setor nao encontrado");
        String filtro=" FROM public.usuario u JOIN public.tecnico_setor ts ON ts.tecnico_id=u.id JOIN public.setor s ON s.id=ts.setor_id WHERE u.ativo AND u.perfil='TECNICO' AND ts.habilitado AND s.ativo AND ts.setor_id=?";
        long total=jdbc.queryForObject("SELECT count(*)"+filtro,Long.class,setorId);
        var itens=jdbc.query("SELECT u.id,u.nome,(SELECT count(*) FROM public.ordem_servico o WHERE o.tecnico_id=u.id AND o.status IN ('EM_ANDAMENTO','AGUARDANDO_ENCERRAMENTO')) AS ordens_ativas"+filtro+" ORDER BY ordens_ativas,u.id LIMIT ? OFFSET ?",
            (r,i)->new Tecnico(r.getLong("id"),r.getString("nome"),r.getLong("ordens_ativas")),setorId,tamanho,(long)pagina*tamanho);
        return new Pagina<>(itens,pagina,tamanho,total);
    }
    @Transactional
    public Habilitacao habilitar(long setorId,long tecnicoId,long ator,Habilitar dados) {
        id(setorId);id(tecnicoId);
        if(dados==null||dados.habilitado()==null) throw invalido("Informe habilitado como true ou false");
        iniciar(ator,true);
        var setores=jdbc.queryForList("SELECT ativo FROM public.setor WHERE id=? FOR SHARE",Boolean.class,setorId);
        if(setores.isEmpty()) throw new OrdemErro(404,"SETOR_NAO_ENCONTRADO","Setor nao encontrado");
        var tecnicos=jdbc.queryForList("SELECT ativo AND perfil='TECNICO' FROM public.usuario WHERE id=? FOR SHARE",Boolean.class,tecnicoId);
        if(tecnicos.isEmpty()) throw new OrdemErro(404,"USUARIO_NAO_ENCONTRADO","Usuario nao encontrado");
        if(dados.habilitado()&&(!setores.getFirst()||!tecnicos.getFirst())) throw new OrdemErro(409,"HABILITACAO_INVALIDA","Escolha um setor ativo e um usuario ativo com perfil TECNICO");
        jdbc.update("INSERT INTO public.tecnico_setor(tecnico_id,setor_id,habilitado) VALUES (?,?,?) ON CONFLICT(tecnico_id,setor_id) DO UPDATE SET habilitado=excluded.habilitado",tecnicoId,setorId,dados.habilitado());
        return new Habilitacao(setorId,tecnicoId,dados.habilitado());
    }
}
