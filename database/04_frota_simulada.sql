BEGIN;

INSERT INTO public.setor (nome, descricao)
VALUES ('Setor Norte', 'Simulação de telemetria'),
       ('Setor Sul', 'Simulação de telemetria'),
       ('Setor Leste', 'Simulação de telemetria'),
       ('Setor Oeste', 'Simulação de telemetria')
ON CONFLICT DO NOTHING;

INSERT INTO public.maquina (id, nome, setor_id)
SELECT d.id, d.nome, s.id
FROM (VALUES
    ('COLHEITA-01', 'Colheitadeira 01', 'Setor Norte'),
    ('PLANTADEIRA-01', 'Plantadeira 01', 'Setor Norte'),
    ('TRATOR-01', 'Trator 01', 'Setor Norte'),
    ('COLHEITA-02', 'Colheitadeira 02', 'Setor Sul'),
    ('SEMEADORA-01', 'Semeadora 01', 'Setor Sul'),
    ('TRATOR-02', 'Trator 02', 'Setor Sul'),
    ('COLHEITA-03', 'Colheitadeira 03', 'Setor Leste'),
    ('PLANTADEIRA-02', 'Plantadeira 02', 'Setor Leste'),
    ('SEMEADORA-02', 'Semeadora 02', 'Setor Leste'),
    ('PLANTADEIRA-03', 'Plantadeira 03', 'Setor Oeste'),
    ('SEMEADORA-03', 'Semeadora 03', 'Setor Oeste'),
    ('TRATOR-03', 'Trator 03', 'Setor Oeste')
) AS d(id, nome, setor)
JOIN public.setor s ON lower(btrim(s.nome)) = lower(d.setor)
ON CONFLICT (id) DO NOTHING;

COMMIT;
