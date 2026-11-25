CREATE VIEW vw_estado_maquina AS
SELECT m.id AS maquina_id, m.nome AS maquina_nome, m.ativa AS maquina_ativa,
       m.setor_id, s.nome AS setor_nome,
       l.id AS leitura_id, l.medida_em, r.recebida_em AS ultimo_recebimento_em,
       l.score_falha, l.limiar, l.falha, l.status_analise,
       (r.recebida_em IS NULL OR statement_timestamp() - r.recebida_em > INTERVAL '30 seconds') AS sem_atualizacao,
       CASE
           WHEN r.recebida_em IS NULL OR statement_timestamp() - r.recebida_em > INTERVAL '30 seconds' THEN 'SEM_ATUALIZACAO'
           WHEN l.status_analise = 'PENDENTE' THEN 'ANALISE_PENDENTE'
           WHEN l.status_analise = 'INDISPONIVEL' THEN 'ANALISE_INDISPONIVEL'
           WHEN l.falha THEN 'ATENCAO'
           ELSE 'NORMAL'
       END AS estado,
       a.id AS alerta_id, a.status AS alerta_status
FROM maquina m
JOIN setor s ON s.id = m.setor_id
LEFT JOIN LATERAL (
    SELECT * FROM leitura WHERE machine_id = m.id
    ORDER BY medida_em DESC NULLS LAST, recebida_em DESC, id DESC LIMIT 1
) l ON TRUE
LEFT JOIN LATERAL (
    SELECT recebida_em FROM leitura WHERE machine_id = m.id
    ORDER BY recebida_em DESC, id DESC LIMIT 1
) r ON TRUE
LEFT JOIN alerta a ON a.maquina_id = m.id AND a.status <> 'ENCERRADO';

CREATE VIEW vw_ordens_servico AS
SELECT o.id, o.numero, o.alerta_id, o.maquina_id, m.nome AS maquina_nome,
       o.setor_id, s.nome AS setor_nome, o.titulo, o.descricao, o.prioridade,
       o.prazo_previsto, o.status, o.modo_atribuicao, o.tecnico_id,
       u.nome AS tecnico_nome, o.criado_por, o.criada_em, o.atualizada_em,
       o.iniciada_em, o.concluida_em, o.cancelada_em, o.relato_final
FROM ordem_servico o
JOIN maquina m ON m.id = o.maquina_id
JOIN setor s ON s.id = o.setor_id
LEFT JOIN usuario u ON u.id = o.tecnico_id;
