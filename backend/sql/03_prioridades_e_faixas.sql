-- Executar depois de 02_fluxo_manutencao.sql no banco existente.
BEGIN;
ALTER TABLE public.ordem_servico
    ADD COLUMN IF NOT EXISTS prioridade_por BIGINT REFERENCES public.usuario(id),
    ALTER COLUMN prioridade DROP DEFAULT, ALTER COLUMN prioridade DROP NOT NULL;

CREATE TABLE IF NOT EXISTS public.faixa_sensor_maquina (
    maquina_id VARCHAR(30) NOT NULL REFERENCES public.maquina(id),
    sensor TEXT NOT NULL CHECK (sensor IN ('rpm','torque','ar_c','processo_c','desgaste')),
    minimo DOUBLE PRECISION, maximo DOUBLE PRECISION,
    critico_min DOUBLE PRECISION, critico_max DOUBLE PRECISION,
    origem TEXT NOT NULL,
    PRIMARY KEY (maquina_id,sensor),
    CHECK (minimo IS NOT NULL OR maximo IS NOT NULL),
    CHECK (minimo IS NULL OR maximo IS NULL OR minimo < maximo),
    CHECK (critico_min IS NULL OR (minimo IS NOT NULL AND critico_min < minimo)),
    CHECK (critico_max IS NULL OR (maximo IS NOT NULL AND critico_max > maximo))
);
-- Somente os seis cadastros identificados no simulador. As faixas originais de
-- temperatura do motor e pressão do óleo não correspondem aos sensores recebidos.
INSERT INTO public.faixa_sensor_maquina(maquina_id,sensor,minimo,maximo,critico_min,critico_max,origem)
SELECT m.id,'rpm',c.minimo,c.maximo,c.critico_min,c.critico_max,'Faixa de demonstração da categoria'
FROM (VALUES ('COLHEITA-',1700,2300,1400,2500),('TRATOR-',1500,2200,1300,2400)) c(prefixo,minimo,maximo,critico_min,critico_max)
JOIN public.maquina m ON m.id IN (c.prefixo||'01',c.prefixo||'02',c.prefixo||'03')
JOIN public.setor s ON s.id=m.setor_id WHERE lower(s.nome)='simulacao'
AND (m.descricao LIKE 'Demonstracao simulada:%' OR m.descricao='Cadastro sintetico do AgroSense')
ON CONFLICT (maquina_id,sensor) DO NOTHING;

CREATE OR REPLACE VIEW public.telemetria_faixas AS
SELECT m.id AS maquina_id, l.recebida_em,
    CASE WHEN NOT m.ativa OR NOT s.ativo THEN 'INATIVA'
         WHEN l.id IS NULL OR l.recebida_em <= statement_timestamp()-interval '30 seconds' THEN 'SEM_COMUNICACAO'
         WHEN ev.n = 0 THEN 'SEM_FAIXA'
         WHEN ev.pior = 2 THEN 'CRITICO' WHEN ev.pior = 1 THEN 'ATENCAO'
         WHEN ev.validas < ev.n THEN 'INCOMPLETA' ELSE 'DENTRO' END AS situacao,
    coalesce(ev.sensores,'[]'::jsonb) AS sensores
FROM public.maquina m JOIN public.setor s ON s.id=m.setor_id
LEFT JOIN LATERAL (SELECT * FROM public.leitura WHERE machine_id=m.id ORDER BY recebida_em DESC,id DESC LIMIT 1) l ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS n,count(x.valor) AS validas,max(y.nivel) AS pior,
        jsonb_agg(jsonb_build_object('sensor',f.sensor,'valor',x.valor,'minimo',f.minimo,'maximo',f.maximo,
            'criticoMin',f.critico_min,'criticoMax',f.critico_max,'origem',f.origem,'nivel',y.nivel) ORDER BY f.sensor) AS sensores
    FROM public.faixa_sensor_maquina f
    CROSS JOIN LATERAL (SELECT CASE f.sensor WHEN 'rpm' THEN l.rotational_speed WHEN 'torque' THEN l.torque
        WHEN 'ar_c' THEN l.air_temperature-273.15 WHEN 'processo_c' THEN l.process_temperature-273.15
        WHEN 'desgaste' THEN l.tool_wear END AS valor) x
    CROSS JOIN LATERAL (SELECT CASE WHEN x.valor IS NULL THEN NULL
        WHEN x.valor <= f.critico_min OR x.valor >= f.critico_max THEN 2
        WHEN x.valor < f.minimo OR x.valor > f.maximo THEN 1 ELSE 0 END AS nivel) y
    WHERE f.maquina_id=m.id
) ev ON true;
GRANT SELECT ON public.telemetria_faixas TO agrosense;
COMMIT;
