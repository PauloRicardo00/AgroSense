BEGIN;

GRANT EXECUTE ON FUNCTION public.criar_ordem_servico(BIGINT, BIGINT, TEXT, BIGINT, TEXT, TIMESTAMPTZ, TEXT) TO agrosense;
GRANT EXECUTE ON FUNCTION public.atribuir_ordem_servico(BIGINT, BIGINT, TEXT, BIGINT) TO agrosense;
GRANT EXECUTE ON FUNCTION public.alterar_status_ordem_servico(BIGINT, BIGINT, TEXT, TEXT) TO agrosense;

GRANT SELECT, INSERT, UPDATE ON public.ordem_servico TO agrosense;
GRANT SELECT, INSERT ON public.os_historico TO agrosense;
GRANT SELECT, INSERT, UPDATE ON public.tecnico_setor TO agrosense;
GRANT USAGE, SELECT ON SEQUENCE public.ordem_servico_id_seq, public.os_historico_id_seq TO agrosense;

COMMIT;

SELECT
    has_function_privilege('agrosense', 'public.criar_ordem_servico(bigint,bigint,text,bigint,text,timestamp with time zone,text)', 'EXECUTE') AS pode_criar,
    has_function_privilege('agrosense', 'public.atribuir_ordem_servico(bigint,bigint,text,bigint)', 'EXECUTE') AS pode_atribuir,
    has_function_privilege('agrosense', 'public.alterar_status_ordem_servico(bigint,bigint,text,text)', 'EXECUTE') AS pode_atualizar_status;
