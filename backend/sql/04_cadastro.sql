BEGIN;
ALTER TABLE public.maquina ADD COLUMN IF NOT EXISTS modelo varchar(160);
ALTER TABLE public.setor ADD COLUMN IF NOT EXISTS hectares numeric CHECK (hectares >= 0);

COMMIT;
