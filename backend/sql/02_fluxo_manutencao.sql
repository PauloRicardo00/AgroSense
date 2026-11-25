-- Execute uma vez como proprietário das tabelas no banco AgroSense existente.
BEGIN;
LOCK TABLE public.ordem_servico IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT maquina_id FROM public.ordem_servico
        WHERE status NOT IN ('CONCLUIDA','CANCELADA','RECUSADA')
        GROUP BY maquina_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Há máquinas com várias OS ativas. Revise esses registros antes da migração.';
    END IF;
END $$;
ALTER TABLE public.ordem_servico ALTER COLUMN alerta_id DROP NOT NULL,
    ALTER COLUMN modo_atribuicao DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS parecer JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE public.ordem_servico DROP CONSTRAINT IF EXISTS ordem_servico_status_check;
ALTER TABLE public.ordem_servico ADD CONSTRAINT ordem_servico_status_check CHECK (status IN
    ('AGUARDANDO_ATRIBUICAO','ABERTA','CANCELADA','AGUARDANDO_AUTORIZACAO','AUTORIZADA',
     'EM_ANDAMENTO','AGUARDANDO_ENCERRAMENTO','CONCLUIDA','RECUSADA'));
-- Sem alerta vinculado, a própria OS continua referenciando a máquina.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conrelid='public.ordem_servico'::regclass AND conname='fk_os_maquina') THEN
        ALTER TABLE public.ordem_servico ADD CONSTRAINT fk_os_maquina
            FOREIGN KEY (maquina_id) REFERENCES public.maquina(id);
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS ux_os_maquina_em_atendimento ON public.ordem_servico(maquina_id)
    WHERE status NOT IN ('CONCLUIDA','CANCELADA','RECUSADA');
COMMIT;
